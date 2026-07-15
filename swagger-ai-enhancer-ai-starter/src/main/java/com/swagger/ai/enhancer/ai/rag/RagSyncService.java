package com.swagger.ai.enhancer.ai.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.dto.RagConfigDto;
import com.swagger.ai.enhancer.ai.exception.DimensionMismatchException;
import com.swagger.ai.enhancer.ai.entity.RagSyncMetadataEntity;
import com.swagger.ai.enhancer.ai.mapper.RagSyncMetadataMapper;
import com.swagger.ai.enhancer.ai.service.RagConfigService;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider.SearchResult;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider.VectorDoc;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 增量同步核心服务：扫描知识库目录 → SHA-256 比对 → 新增/变更/删除三种处理。
 *
 * 元数据持久化到 MySQL rag_sync_metadata 表（替换原本地 JSON 方案）：
 *   - 每个 docType + filePath 唯一一条记录
 *   - 记录 file_hash / chunk_count / last_synced_at
 *
 * 支持按 docType 分别同步：
 *   sync(String docType) — 同步指定 docType 的知识库
 *   sync()                — 全量同步：遍历所有已配置 docType 聚合结果
 */
@Slf4j
public class RagSyncService {

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            "txt", "md", "pdf", "docx", "xlsx", "csv", "html",
            "chm", "ppt", "pptx", "epub", "rtf",
            "odt", "ods", "xml", "json", "log",
            "yml", "yaml", "rst", "wiki", "mediawiki",
            "tex", "mobi"
    );

    private final AiEnhancerProperties properties;
    private final DocumentLoader documentLoader;
    private final TextSplitter textSplitter;
    private final EmbeddingService embeddingService;
    private final VectorStoreProvider vectorStoreProvider;
    private final RagSyncMetadataMapper metadataMapper;
    private final RagConfigService ragConfigService;

    public RagSyncService(AiEnhancerProperties properties,
                          DocumentLoader documentLoader,
                          TextSplitter textSplitter,
                          EmbeddingService embeddingService,
                          VectorStoreProvider vectorStoreProvider,
                          RagSyncMetadataMapper metadataMapper,
                          RagConfigService ragConfigService) {
        this.properties = properties;
        this.documentLoader = documentLoader;
        this.textSplitter = textSplitter;
        this.embeddingService = embeddingService;
        this.vectorStoreProvider = vectorStoreProvider;
        this.metadataMapper = metadataMapper;
        this.ragConfigService = ragConfigService;
    }

    /**
     * 全量同步：遍历 rag.knowledgePaths 中所有已配置 docType，
     * 逐一调用 sync(String docType)，聚合结果返回。
     */
    public SyncResult sync() {
        AiEnhancerProperties.RagConfig rag = properties.getRag();
        Map<String, String> kps = rag.getKnowledgePaths();
        List<String> docTypes = new ArrayList<>();
        if (kps != null) {
            docTypes.addAll(kps.keySet());
        }
        // 兼容旧配置：若只有 knowledgePath 有值，则视为 docType = "default"
        if (docTypes.isEmpty() && rag.getKnowledgePath() != null && !rag.getKnowledgePath().isBlank()) {
            docTypes.add("default");
        }

        if (docTypes.isEmpty()) {
            return SyncResult.builder()
                    .status("skipped")
                    .message("未配置任何知识库路径 (rag.knowledge-paths / rag.knowledge-path)")
                    .build();
        }

        SyncResult merged = SyncResult.builder()
                .status("ok")
                .docType("all")
                .addedFiles(0)
                .updatedFiles(0)
                .deletedFiles(0)
                .totalChunks(0)
                .failedFiles(new ArrayList<>())
                .perDocType(new HashMap<>())
                .build();

        for (String docType : docTypes) {
            SyncResult r = sync(docType);
            merged.setAddedFiles(merged.getAddedFiles() + r.getAddedFiles());
            merged.setUpdatedFiles(merged.getUpdatedFiles() + r.getUpdatedFiles());
            merged.setDeletedFiles(merged.getDeletedFiles() + r.getDeletedFiles());
            merged.setTotalChunks(merged.getTotalChunks() + r.getTotalChunks());
            if (r.getFailedFiles() != null) {
                merged.getFailedFiles().addAll(r.getFailedFiles());
            }
            merged.getPerDocType().put(docType, r);
        }
        return merged;
    }

    /**
     * 同步选项：允许调用方临时覆盖配置中的 knowledgePath/collectionName/vectorStore。
     * 所有字段可为 null；null 表示沿用 properties 中的值。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncOptions {
        /** 若不为空，则覆盖 properties 中的 knowledgePaths[docType] 取到 */
        private String knowledgePath;
        /** 若不为空，则覆盖自动拼接的 collectionName */
        private String collectionName;
        /** 预留：未来可用于切换 vectorStore */
        private String vectorStore;
    }

    /**
     * 与 {@link #sync(String, SyncOptions)} 的兼容入口（无选项）。
     */
    public SyncResult sync(String docType) {
        return sync(docType, null);
    }

    /**
     * 执行指定 docType 的增量同步：
     * 1. knowledgePath：优先取 options.knowledgePath，其次从 rag.knowledgePaths[docType]
     * 2. collectionName：优先取 options.collectionName（并规范化，其次自动拼接
     * 3. 递归扫描 → SHA-256 比对 → load/split/embed/insert，删除过期文件
     * 4. 将元数据写入 rag_sync_metadata 表（upsert）
     */
    public SyncResult sync(String docType, SyncOptions options) {
        if (docType == null || docType.isBlank()) {
            return SyncResult.builder()
                    .status("error")
                    .message("docType 不能为空")
                    .build();
        }
        AiEnhancerProperties.RagConfig rag = properties.getRag();

        // 路径：options.knowledgePath → properties.knowledgePaths → 兼容旧配置 knowledgePath
        String path = (options != null && options.getKnowledgePath() != null
                && !options.getKnowledgePath().isBlank()) ? options.getKnowledgePath() : null;
        if (path == null) {
            Map<String, String> kps = rag.getKnowledgePaths();
            if (kps != null && kps.containsKey(docType)) {
                path = kps.get(docType);
            }
        }
        if ((path == null || path.isBlank()) && "default".equals(docType)
                && rag.getKnowledgePath() != null && !rag.getKnowledgePath().isBlank()) {
            path = rag.getKnowledgePath();
        }

        if (path == null || path.isBlank()) {
            return SyncResult.builder()
                    .status("skipped")
                    .message("docType=" + docType + " 未配置 knowledgePaths 路径（可在面板中直接填入并同步）")
                    .docType(docType)
                    .build();
        }

        Path root = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return SyncResult.builder()
                    .status("error")
                    .message("知识库目录不存在或不是目录：" + root)
                    .docType(docType)
                    .build();
        }

        // collectionName：options.collectionName 优先 > 用户自定义（RagConfigService） > 公式拼接
        String collectionName;
        if (options != null && options.getCollectionName() != null
                && !options.getCollectionName().isBlank()) {
            // 显式传入的 collectionName 优先
            collectionName = options.getCollectionName().replace("-", "_");
        } else {
            // 优先读取用户自定义的 collectionName，未配置时降级使用公式
            try {
                RagConfigDto configDto = ragConfigService.getConfigOrDefault(docType);
                if (configDto != null && configDto.getCollectionName() != null
                        && !configDto.getCollectionName().isBlank()) {
                    collectionName = configDto.getCollectionName().replace("-", "_");
                } else {
                    collectionName = rag.getCollectionPrefix() + "_" + docType.replace("-", "_");
                }
            } catch (Exception e) {
                log.warn("[rag-sync] 读取 docType={} 的自定义 collectionName 失败，降级使用默认公式: {}",
                        docType, e.getMessage());
                collectionName = rag.getCollectionPrefix() + "_" + docType.replace("-", "_");
            }
        }

        List<Path> files = scanFiles(root);
        if (files.isEmpty()) {
            return SyncResult.builder()
                    .status("empty")
                    .message("目录下无支持的文件（支持：txt/md/pdf/docx/xlsx/csv/html）：" + root)
                    .docType(docType)
                    .collection(collectionName)
                    .build();
        }

        try {
            vectorStoreProvider.createCollection(collectionName, rag.getDimension());
        } catch (DimensionMismatchException e) {
            log.warn("[rag-sync] 维度不匹配：{}", e.getMessage());
            return SyncResult.builder()
                    .status("dimension_mismatch")
                    .message("向量维度不匹配（旧维度=" + e.getOldDimension()
                            + "，新维度=" + e.getNewDimension()
                            + "，Collection=" + e.getCollectionName()
                            + "）。请先删除旧 Collection 后重新同步。")
                    .docType(docType)
                    .collection(collectionName)
                    .build();
        }

        // 从 rag_sync_metadata 加载该 docType 的已知文件元数据：filePath -> fileHash
        Map<String, RagSyncMetadataEntity> oldMetadata = loadMetadataFor(docType);
        // 构建简单哈希索引
        Map<String, String> oldHashMap = new HashMap<>();
        for (Map.Entry<String, RagSyncMetadataEntity> e : oldMetadata.entrySet()) {
            oldHashMap.put(e.getKey(), e.getValue().getFileHash());
        }

        Set<String> currentPaths = new HashSet<>();
        // 用于变更/新增文件的元数据缓存，避免逐条 DB 写
        List<RagSyncMetadataEntity> toUpsert = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();

        int addedFiles = 0;
        int updatedFiles = 0;
        int deletedFiles = 0;
        int totalChunks = 0;
        List<String> failedFiles = new ArrayList<>();

        for (Path file : files) {
            String filePath = file.toString();
            currentPaths.add(filePath);
            try {
                String hash = computeSha256(file);
                String oldHash = oldHashMap.get(filePath);

                if (oldHash == null) {
                    // 新增
                    int chunks = processFile(collectionName, filePath, file);
                    totalChunks += chunks;
                    if (chunks > 0) {
                        toUpsert.add(buildEntity(docType, filePath, hash, chunks));
                        addedFiles++;
                        log.info("[{}] 新增文件已同步：{}，{} 段", docType, filePath, chunks);
                    }
                } else if (!hash.equals(oldHash)) {
                    // 变更 → 删除旧向量后重新插入
                    try {
                        vectorStoreProvider.deleteByFile(collectionName, filePath);
                    } catch (Exception ignored) {
                        // 删除失败不影响继续处理
                    }
                    int chunks = processFile(collectionName, filePath, file);
                    totalChunks += chunks;
                    if (chunks > 0) {
                        toUpsert.add(buildEntity(docType, filePath, hash, chunks));
                        updatedFiles++;
                        log.info("[{}] 变更文件已重同步：{}，{} 段", docType, filePath, chunks);
                    } else {
                        // 文件无有效内容 → 数据库中删除旧记录
                        toDelete.add(filePath);
                    }
                } else {
                    // 未变，跳过；累加已知 chunk 数量用于统计
                    RagSyncMetadataEntity existing = oldMetadata.get(filePath);
                    if (existing != null && existing.getChunkCount() != null) {
                        totalChunks += existing.getChunkCount();
                    }
                }
            } catch (Exception e) {
                log.error("[{}] 处理文件失败：{}，原因：{}", docType, filePath, e.getMessage());
                failedFiles.add(filePath + "（" + e.getMessage() + "）");
            }
        }

        // 处理元数据中存在但磁盘上已不存在的文件
        for (String metaPath : oldHashMap.keySet()) {
            if (!currentPaths.contains(metaPath)) {
                try {
                    vectorStoreProvider.deleteByFile(collectionName, metaPath);
                    toDelete.add(metaPath);
                    deletedFiles++;
                    log.info("[{}] 已从向量库删除旧文件：{}", docType, metaPath);
                } catch (Exception e) {
                    log.warn("[{}] 删除向量失败：{}，原因：{}", docType, metaPath, e.getMessage());
                }
            }
        }

        // 持久化元数据：逐条 upsert（docType + filePath 唯一键）
        for (RagSyncMetadataEntity entity : toUpsert) {
            try {
                upsertMetadata(entity);
            } catch (Exception e) {
                log.warn("[{}] 保存元数据失败（{}）：{}", docType, entity.getFilePath(), e.getMessage());
            }
        }
        for (String deletePath : toDelete) {
            try {
                deleteMetadata(docType, deletePath);
            } catch (Exception e) {
                log.warn("[{}] 删除元数据失败（{}）：{}", docType, deletePath, e.getMessage());
            }
        }

        log.info("[{}] 增量同步完成：新增 {}，变更 {}，删除 {}，总 chunks {}",
                docType, addedFiles, updatedFiles, deletedFiles, totalChunks);

        return SyncResult.builder()
                .status("ok")
                .docType(docType)
                .addedFiles(addedFiles)
                .updatedFiles(updatedFiles)
                .deletedFiles(deletedFiles)
                .totalChunks(totalChunks)
                .failedFiles(failedFiles)
                .collection(collectionName)
                .build();
    }

    // ==================== 元数据读写辅助 ====================

    /**
     * 读取指定 docType 的所有元数据。
     * 返回 Map(filePath -> entity)，便于哈希比对。
     */
    private Map<String, RagSyncMetadataEntity> loadMetadataFor(String docType) {
        Map<String, RagSyncMetadataEntity> result = new HashMap<>();
        try {
            LambdaQueryWrapper<RagSyncMetadataEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(RagSyncMetadataEntity::getDocType, docType);
            List<RagSyncMetadataEntity> rows = metadataMapper.selectList(qw);
            if (rows == null) {
                return result;
            }
            for (RagSyncMetadataEntity row : rows) {
                if (row.getFilePath() != null) {
                    result.put(row.getFilePath(), row);
                }
            }
        } catch (Exception e) {
            log.warn("[{}] 从 rag_sync_metadata 加载元数据失败（{}），按空处理继续同步。",
                    docType, e.getMessage());
        }
        return result;
    }

    /**
     * 构建一个未持久化的元数据实体。
     */
    private static RagSyncMetadataEntity buildEntity(String docType,
                                                      String filePath,
                                                      String hash,
                                                      int chunkCount) {
        RagSyncMetadataEntity e = new RagSyncMetadataEntity();
        e.setDocType(docType);
        e.setFilePath(filePath);
        e.setFileHash(hash);
        e.setChunkCount(chunkCount);
        e.setLastSyncedAt(LocalDateTime.now());
        return e;
    }

    /**
     * upsert 一条元数据：先按 (doc_type, file_path) 查询；对比 file_hash / chunk_count，无变化则跳过 UPDATE。
     * 所有数据库操作均包裹 try-catch，异常时抛出让上层 sync 方法兜底处理。
     */
    private void upsertMetadata(RagSyncMetadataEntity entity) {
        // 1) 按唯一索引查询是否存在；异常降级为"不存在"
        RagSyncMetadataEntity existing = null;
        try {
            LambdaQueryWrapper<RagSyncMetadataEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(RagSyncMetadataEntity::getDocType, entity.getDocType())
                    .eq(RagSyncMetadataEntity::getFilePath, entity.getFilePath());
            existing = metadataMapper.selectOne(qw);
        } catch (Exception e) {
            log.warn("[rag-sync] 查询元数据失败（docType={}, filePath={}），按不存在处理: {}",
                    entity.getDocType(), entity.getFilePath(), e.getMessage());
            existing = null;
        }

        if (existing != null) {
            // 2) 逐字段对比：file_hash 与 chunk_count（last_synced_at 由数据库自动更新，无需对比）
            boolean hashChanged = !java.util.Objects.equals(
                    trim(existing.getFileHash()), trim(entity.getFileHash()));
            boolean chunkChanged = !java.util.Objects.equals(
                    existing.getChunkCount(), entity.getChunkCount());

            if (!hashChanged && !chunkChanged) {
                log.debug("[rag-sync] 元数据无变化，跳过更新：{}", entity.getFilePath());
                return;
            }

            // 3) 复用 id 执行 updateById，异常捕获
            entity.setId(existing.getId());
            try {
                metadataMapper.updateById(entity);
            } catch (Exception e) {
                log.error("[rag-sync] 更新元数据失败（docType={}, filePath={}）: {}",
                        entity.getDocType(), entity.getFilePath(), e.getMessage());
                throw new RuntimeException("RAG 元数据更新失败", e);
            }
        } else {
            // 4) 不存在则 insert，异常捕获
            try {
                metadataMapper.insert(entity);
            } catch (Exception e) {
                log.error("[rag-sync] 新增元数据失败（docType={}, filePath={}）: {}",
                        entity.getDocType(), entity.getFilePath(), e.getMessage());
                throw new RuntimeException("RAG 元数据插入失败", e);
            }
        }
    }

    /** null / 空白字符串统一为 ""，避免 "  " 与 "" 或 null 被误判为不同 */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private void deleteMetadata(String docType, String filePath) {
        // 1) 先按唯一索引查询确认记录存在；异常时抛给上层兜底
        LambdaQueryWrapper<RagSyncMetadataEntity> qw = new LambdaQueryWrapper<>();
        qw.eq(RagSyncMetadataEntity::getDocType, docType)
                .eq(RagSyncMetadataEntity::getFilePath, filePath);

        RagSyncMetadataEntity existing;
        try {
            existing = metadataMapper.selectOne(qw);
        } catch (Exception e) {
            log.warn("[rag-sync] 查询元数据失败（docType={}, filePath={}）: {}",
                    docType, filePath, e.getMessage());
            throw new RuntimeException("RAG 元数据查询失败", e);
        }

        // 2) 记录不存在 → 跳过删除，debug 日志
        if (existing == null) {
            log.debug("[rag-sync] 元数据记录不存在，跳过删除：docType={}, filePath={}",
                    docType, filePath);
            return;
        }

        // 3) 按主键删除，异常捕获
        try {
            int rows = metadataMapper.deleteById(existing.getId());
            log.info("[rag-sync] 已删除元数据：docType={}, filePath={}，影响 {} 行",
                    docType, filePath, rows);
        } catch (Exception e) {
            log.error("[rag-sync] 删除元数据失败（docType={}, filePath={}）: {}",
                    docType, filePath, e.getMessage());
            throw new RuntimeException("RAG 元数据删除失败", e);
        }
    }

    // ==================== 文件处理辅助 ====================

    private static List<Path> scanFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString().toLowerCase();
                // 跳过元数据相关文件名（保留向后兼容）
                if (name.equals(".rag_metadata.json")) {
                    return;
                }
                int dot = name.lastIndexOf('.');
                String ext = dot < 0 ? "" : name.substring(dot + 1);
                if (SUPPORTED_EXTENSIONS.contains(ext)) {
                    files.add(file);
                }
            });
        } catch (IOException e) {
            log.error("扫描目录失败：{}", e.getMessage());
        }
        return files;
    }

    private int processFile(String collectionName, String filePath, Path file) throws IOException {
        String text = documentLoader.load(file);
        List<String> chunks = textSplitter.split(text);
        if (chunks.isEmpty()) {
            log.info("文件无有效内容，跳过：{}", filePath);
            return 0;
        }
        List<List<Double>> vectors = embeddingService.embedBatch(chunks);
        List<VectorDoc> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("file_path", filePath);
            metadata.put("chunk_index", String.valueOf(i));
            docs.add(VectorDoc.builder()
                    .content(chunks.get(i))
                    .embedding(vectors.get(i))
                    .metadata(metadata)
                    .build());
        }
        vectorStoreProvider.insert(collectionName, docs);
        return chunks.size();
    }

    private static String computeSha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 算法不可用：" + e.getMessage(), e);
        }
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== DTO / 健康检查 ====================

    /**
     * 同步结果，用于控制器返回 JSON 响应。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncResult {
        private String status;
        private String message;
        private String docType;
        private int addedFiles;
        private int updatedFiles;
        private int deletedFiles;
        private int totalChunks;
        private List<String> failedFiles;
        private String collection;
        /** 全量同步时，各 docType 子结果（用于聚合展示） */
        private Map<String, SyncResult> perDocType;
    }

    /**
     * 健康检查辅助方法，暴露给控制器使用。
     */
    public SearchResult healthProbe() {
        return null;
    }
}
