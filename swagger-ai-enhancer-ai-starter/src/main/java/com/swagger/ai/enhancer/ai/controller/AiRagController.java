package com.swagger.ai.enhancer.ai.controller;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.rag.RagSyncService;
import com.swagger.ai.enhancer.ai.rag.RagSyncService.SyncResult;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import com.swagger.ai.enhancer.ai.service.RagMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 服务 REST 控制器。
 *   POST /api/ai/rag/sync?docType=xxx  — 增量同步（可选 docType，不传则全量）
 *   GET  /api/ai/rag/health              — 健康检查（向量数据库连接 + 所有 docType 的 collection 状态）
 *   POST /api/ai/rag/test-connection     — 临时客户端测试向量库连通性（milvus/qdrant/pgvector/weaviate）
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/rag")
@ConditionalOnProperty(name = "swagger-ai-enhancer.ai.rag.enabled",
        havingValue = "true", matchIfMissing = false)
public class AiRagController {

    private final AiEnhancerProperties properties;
    private final RagSyncService ragSyncService;
    private final VectorStoreProvider vectorStoreProvider;
    private final RagMetricsService ragMetricsService;

    public AiRagController(AiEnhancerProperties properties,
                           RagSyncService ragSyncService,
                           VectorStoreProvider vectorStoreProvider,
                           RagMetricsService ragMetricsService) {
        this.properties = properties;
        this.ragSyncService = ragSyncService;
        this.vectorStoreProvider = vectorStoreProvider;
        this.ragMetricsService = ragMetricsService;
    }

    @PostMapping(value = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> syncKnowledgeBase(
            @RequestParam(name = "docType", required = false) String docType,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            // 从请求体中解析前端临时传入的选项（knowledgePath/collectionName/vectorStore）。
            // 若未传入请求体，保持原逻辑（从 properties 读取配置）。
            RagSyncService.SyncOptions options = null;
            if (body != null && !body.isEmpty()) {
                RagSyncService.SyncOptions.SyncOptionsBuilder b = RagSyncService.SyncOptions.builder();
                Object kp = body.get("knowledgePath");
                if (kp instanceof String && !((String) kp).isBlank()) {
                    b.knowledgePath((String) kp);
                }
                Object cn = body.get("collectionName");
                if (cn instanceof String && !((String) cn).isBlank()) {
                    b.collectionName((String) cn);
                }
                Object vs = body.get("vectorStore");
                if (vs instanceof String && !((String) vs).isBlank()) {
                    b.vectorStore((String) vs);
                }
                Object dt = body.get("docType");
                if (dt instanceof String && !((String) dt).isBlank()
                        && (docType == null || docType.isBlank())) {
                    docType = (String) dt;
                }
                options = b.build();
            }

            SyncResult result;
            if (docType != null && !docType.isBlank()) {
                result = ragSyncService.sync(docType, options);
            } else {
                result = ragSyncService.sync();
            }
            Map<String, Object> respBody = new HashMap<>();
            respBody.put("status", result.getStatus() == null ? "ok" : result.getStatus());
            if (result.getMessage() != null) respBody.put("message", result.getMessage());
            if (result.getDocType() != null) respBody.put("docType", result.getDocType());
            respBody.put("addedFiles", result.getAddedFiles());
            respBody.put("updatedFiles", result.getUpdatedFiles());
            respBody.put("deletedFiles", result.getDeletedFiles());
            respBody.put("totalChunks", result.getTotalChunks());
            if (result.getFailedFiles() != null && !result.getFailedFiles().isEmpty()) {
                respBody.put("failedFiles", result.getFailedFiles());
            }
            if (result.getCollection() != null) respBody.put("collection", result.getCollection());
            if (result.getPerDocType() != null && !result.getPerDocType().isEmpty()) {
                // 简化展示：仅保留每个 docType 的状态 + collection + 数量
                Map<String, Map<String, Object>> sub = new HashMap<>();
                for (Map.Entry<String, SyncResult> e : result.getPerDocType().entrySet()) {
                    SyncResult r = e.getValue();
                    Map<String, Object> info = new HashMap<>();
                    info.put("status", r.getStatus());
                    info.put("collection", r.getCollection());
                    info.put("addedFiles", r.getAddedFiles());
                    info.put("updatedFiles", r.getUpdatedFiles());
                    info.put("deletedFiles", r.getDeletedFiles());
                    info.put("totalChunks", r.getTotalChunks());
                    if (r.getMessage() != null) info.put("message", r.getMessage());
                    sub.put(e.getKey(), info);
                }
                respBody.put("perDocType", sub);
            }
            log.info("RAG 同步完成（docType={}）：新增 {}, 变更 {}, 删除 {}, 总 {}",
                    docType == null ? "all" : docType,
                    result.getAddedFiles(), result.getUpdatedFiles(),
                    result.getDeletedFiles(), result.getTotalChunks());
            return ResponseEntity.ok(respBody);
        } catch (Exception e) {
            log.error("RAG 同步失败（docType={}）：{}", docType, e.getMessage(), e);
            Map<String, Object> respBody = new HashMap<>();
            respBody.put("status", "error");
            respBody.put("message", e.getMessage());
            if (docType != null) respBody.put("docType", docType);
            return ResponseEntity.status(500).body(respBody);
        }
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new HashMap<>();
        AiEnhancerProperties.RagConfig rag = properties.getRag();
        body.put("status", "ok");
        body.put("vectorStore", rag.getVectorStore());
        body.put("collectionPrefix", rag.getCollectionPrefix());

        // 收集所有 docType 并检查对应 collection 状态
        List<String> docTypes = new ArrayList<>();
        Map<String, String> kps = rag.getKnowledgePaths();
        if (kps != null) docTypes.addAll(kps.keySet());
        // 兼容旧配置
        if (docTypes.isEmpty() && rag.getKnowledgePath() != null && !rag.getKnowledgePath().isBlank()) {
            docTypes.add("default");
        }

        if (docTypes.isEmpty()) {
            body.put("note", "rag.knowledge-paths / rag.knowledge-path 均未配置，无法推断 docType");
            // 至少检查一次向量库连接
            try {
                String probeCollection = rag.getCollectionPrefix() + "_probe";
                vectorStoreProvider.collectionExists(probeCollection);
                body.put("vectorStoreReachable", true);
            } catch (Exception e) {
                body.put("status", "error");
                body.put("message", "向量库连接失败：" + e.getMessage());
                return ResponseEntity.status(503).body(body);
            }
            return ResponseEntity.ok(body);
        }

        // 返回每个 docType 的 collection 状态
        Map<String, Map<String, Object>> docTypeStatuses = new HashMap<>();
        boolean anyError = false;
        for (String docType : docTypes) {
            String collectionName = rag.getCollectionPrefix() + "_" + docType.replace("-", "_");
            Map<String, Object> info = new HashMap<>();
            info.put("collection", collectionName);
            try {
                boolean exists = vectorStoreProvider.collectionExists(collectionName);
                info.put("exists", exists);
                info.put("status", exists ? "ok" : "not-created");
                // 同时记录 knowledgePath
                if (kps != null && kps.get(docType) != null) {
                    info.put("knowledgePath", kps.get(docType));
                } else if ("default".equals(docType)) {
                    info.put("knowledgePath", rag.getKnowledgePath());
                }
            } catch (Exception e) {
                info.put("status", "error");
                info.put("message", e.getMessage());
                anyError = true;
            }
            docTypeStatuses.put(docType, info);
        }
        body.put("docTypes", docTypeStatuses);
        if (anyError) {
            body.put("status", "partial-error");
            return ResponseEntity.status(503).body(body);
        }
        return ResponseEntity.ok(body);
    }

    // ============ GET /api/ai/rag/metrics（指标查询） ============

    /**
     * 查询指定 docType 的 RAG 运行指标。
     *   - collectionName：实际使用的向量库 collection 名
     *   - retrievalMetrics：检索统计（总次数、命中数、命中率、平均最高分、最高最高分、分数分布）
     *   - syncStatus：同步统计（文件总数、片段总数、最近一次同步时间）
     *
     * @param docType  必填（不支持不传，因为不同 docType 的 collection 不同）
     */
    @GetMapping(value = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getMetrics(
            @RequestParam(name = "docType") String docType) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            AiEnhancerProperties.RagConfig rag = properties.getRag();
            // collectionName 与 AiController.retrieveRagContext 的推导逻辑保持一致：
            // 优先使用用户在 RagConfig 中自定义的 collectionName，否则使用 {prefix}_{docType}。
            // 这里为了避免在 AiRagController 中强依赖 RagConfigService，使用默认公式；
            // 若用户确有自定义 collectionName 的场景，可在 RagMetricsService 中扩展。
            String collectionName = rag.getCollectionPrefix() + "_" + docType.replace("-", "_");

            body.put("status", "ok");
            body.put("docType", docType);
            body.put("collectionName", collectionName);
            body.put("lowScoreThreshold", 0.4);
            body.put("highScoreThreshold", 0.7);

            // —— 同步统计
            if (ragMetricsService != null) {
                RagMetricsService.SyncMetadataSummary sync = ragMetricsService.getSyncMetadataSummary(docType);
                if (sync != null) {
                    Map<String, Object> syncMap = new LinkedHashMap<>();
                    syncMap.put("totalFiles", sync.getTotalFiles());
                    syncMap.put("totalChunks", sync.getTotalChunks());
                    syncMap.put("lastSyncTime", sync.getLastSyncTime() == null ? null : sync.getLastSyncTime().toString());
                    body.put("syncStatus", syncMap);
                } else {
                    body.put("syncStatus", null);
                }

                // —— 检索统计
                RagMetricsService.RetrievalStats stats = ragMetricsService.getRetrievalStats(docType);
                Map<String, Object> retrieval = new LinkedHashMap<>();
                retrieval.put("totalRetrievals", stats.getTotalRetrievals());
                retrieval.put("hitRetrievals", stats.getHitRetrievals());
                retrieval.put("hitRate", stats.getHitRate());
                retrieval.put("avgTopScore", stats.getAvgTopScore());
                retrieval.put("maxTopScore", stats.getMaxTopScore());

                Map<String, Object> scoreDistribution = new LinkedHashMap<>();
                int totalChunkHits = stats.getHighCount() + stats.getMediumCount() + stats.getLowCount();
                scoreDistribution.put("highCount", stats.getHighCount());
                scoreDistribution.put("mediumCount", stats.getMediumCount());
                scoreDistribution.put("lowCount", stats.getLowCount());
                scoreDistribution.put("highRate", totalChunkHits > 0 ? (double) stats.getHighCount() / totalChunkHits : 0.0);
                scoreDistribution.put("mediumRate", totalChunkHits > 0 ? (double) stats.getMediumCount() / totalChunkHits : 0.0);
                scoreDistribution.put("lowRate", totalChunkHits > 0 ? (double) stats.getLowCount() / totalChunkHits : 0.0);
                retrieval.put("scoreDistribution", scoreDistribution);
                body.put("retrievalMetrics", retrieval);
            } else {
                body.put("syncStatus", null);
                body.put("retrievalMetrics", null);
            }

            log.info("[rag-metrics] docType={}：totalRetrievals={}, hitRate={:.2f}, maxTopScore={:.2f}",
                    docType,
                    body.get("retrievalMetrics") == null ? "n/a" : ((Map<?, ?>) body.get("retrievalMetrics")).get("totalRetrievals"),
                    body.get("retrievalMetrics") == null ? "n/a" : ((Map<?, ?>) body.get("retrievalMetrics")).get("hitRate"),
                    body.get("retrievalMetrics") == null ? "n/a" : ((Map<?, ?>) body.get("retrievalMetrics")).get("maxTopScore"));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("[rag-metrics] 查询失败（docType={}）：{}", docType, e.getMessage(), e);
            body.put("status", "error");
            body.put("message", e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> body = new HashMap<>();
        try {
            AiEnhancerProperties.RagConfig rag = properties.getRag();
            body.put("enabled", rag.isEnabled());
            body.put("vectorStore", rag.getVectorStore());
            body.put("collectionPrefix", rag.getCollectionPrefix());
            body.put("topK", rag.getTopK());
            body.put("similarityThreshold", rag.getSimilarityThreshold());
            body.put("chunkSize", rag.getChunkSize());
            body.put("chunkOverlap", rag.getChunkOverlap());
            body.put("embeddingModel", rag.getEmbeddingModel());
            body.put("dimension", rag.getDimension());

            // —— 知识库路径（docType -> path）
            Map<String, String> kps = rag.getKnowledgePaths();
            if (kps != null && !kps.isEmpty()) {
                body.put("knowledgePaths", kps);
            }
            // 兼容旧 knowledgePath
            if (rag.getKnowledgePath() != null && !rag.getKnowledgePath().isBlank()) {
                Map<String, String> legacy = new HashMap<>();
                legacy.put("default", rag.getKnowledgePath());
                body.put("knowledgePath", legacy);
            }

            // —— 向量库连接信息（仅 host/port，不含密码/密钥）
            Map<String, Object> connections = new HashMap<>();
            if (rag.getMilvus() != null) {
                Map<String, Object> m = new HashMap<>();
                m.put("host", rag.getMilvus().getHost());
                m.put("port", rag.getMilvus().getPort());
                connections.put("milvus", m);
            }
            if (rag.getQdrant() != null) {
                Map<String, Object> q = new HashMap<>();
                q.put("host", rag.getQdrant().getHost());
                q.put("port", rag.getQdrant().getPort());
                connections.put("qdrant", q);
            }
            if (rag.getPgvector() != null) {
                Map<String, Object> p = new HashMap<>();
                p.put("host", rag.getPgvector().getHost());
                p.put("port", rag.getPgvector().getPort());
                p.put("database", rag.getPgvector().getDatabase());
                connections.put("pgvector", p);
            }
            body.put("connections", connections);

            log.info("返回 RAG 配置：vectorStore={}, docTypes={}", rag.getVectorStore(), kps == null ? 0 : kps.size());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("status", "error");
            body.put("message", e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    // ============ POST /api/ai/rag/index /load /release ============

    @PostMapping(value = "/index", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createIndex(
            @RequestParam(name = "docType", required = false) String docType,
            @RequestParam(name = "collectionName", required = false) String collectionName) {
        String resolved = resolveCollectionName(docType, collectionName);
        if (resolved == null || resolved.isBlank()) {
            Map<String, Object> body = new HashMap<>();
            body.put("status", "error");
            body.put("message", "docType 与 collectionName 不可同时为空，请至少提供一个");
            return ResponseEntity.badRequest().body(body);
        }
        try {
            vectorStoreProvider.createIndex(resolved);
            Map<String, Object> body = new HashMap<>();
            body.put("status", "ok");
            body.put("collectionName", resolved);
            body.put("message", "索引创建成功");
            return ResponseEntity.ok(body);
        } catch (com.swagger.ai.enhancer.ai.rag.IndexAlreadyExistsException e) {
            log.info("[rag] 集合 {} 索引已存在，跳过重复创建：{}", resolved, e.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("status", "index_exists");
            body.put("collectionName", resolved);
            body.put("message", e.getMessage() != null ? e.getMessage() : "该 Collection 的索引已存在，无需重复创建");
            return ResponseEntity.ok(body);
        } catch (com.swagger.ai.enhancer.ai.rag.NotApplicableForVectorStoreException e) {
            log.info("[rag] 集合 {} 不支持该操作：{}", resolved, e.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("status", "not_applicable");
            body.put("collectionName", resolved);
            body.put("message", e.getMessage());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("创建索引失败（collection={}）：{}", resolved, e.getMessage(), e);
            Map<String, Object> body = new HashMap<>();
            body.put("status", "error");
            body.put("collectionName", resolved);
            body.put("message", e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    @PostMapping(value = "/load", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> loadCollection(
            @RequestParam(name = "docType", required = false) String docType,
            @RequestParam(name = "collectionName", required = false) String collectionName) {
        String resolved = resolveCollectionName(docType, collectionName);
        if (resolved == null || resolved.isBlank()) {
            Map<String, Object> body = new HashMap<>();
            body.put("status", "error");
            body.put("message", "docType 与 collectionName 不可同时为空，请至少提供一个");
            return ResponseEntity.badRequest().body(body);
        }
        try {
            vectorStoreProvider.loadCollection(resolved);
            Map<String, Object> body = new HashMap<>();
            body.put("status", "ok");
            body.put("collectionName", resolved);
            body.put("message", "集合已加载到内存");
            return ResponseEntity.ok(body);
        } catch (com.swagger.ai.enhancer.ai.rag.NotApplicableForVectorStoreException e) {
            log.info("[rag] 集合 {} 不支持该操作：{}", resolved, e.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("status", "not_applicable");
            body.put("collectionName", resolved);
            body.put("message", e.getMessage());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("加载集合失败（collection={}）：{}", resolved, e.getMessage(), e);
            Map<String, Object> body = new HashMap<>();
            body.put("status", "error");
            body.put("collectionName", resolved);
            body.put("message", e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    @PostMapping(value = "/release", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> releaseCollection(
            @RequestParam(name = "docType", required = false) String docType,
            @RequestParam(name = "collectionName", required = false) String collectionName) {
        String resolved = resolveCollectionName(docType, collectionName);
        if (resolved == null || resolved.isBlank()) {
            Map<String, Object> body = new HashMap<>();
            body.put("status", "error");
            body.put("message", "docType 与 collectionName 不可同时为空，请至少提供一个");
            return ResponseEntity.badRequest().body(body);
        }
        try {
            vectorStoreProvider.releaseCollection(resolved);
            Map<String, Object> body = new HashMap<>();
            body.put("status", "ok");
            body.put("collectionName", resolved);
            body.put("message", "集合已从内存释放");
            return ResponseEntity.ok(body);
        } catch (com.swagger.ai.enhancer.ai.rag.NotApplicableForVectorStoreException e) {
            log.info("[rag] 集合 {} 不支持该操作：{}", resolved, e.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("status", "not_applicable");
            body.put("collectionName", resolved);
            body.put("message", e.getMessage());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("释放集合失败（collection={}）：{}", resolved, e.getMessage(), e);
            Map<String, Object> body = new HashMap<>();
            body.put("status", "error");
            body.put("collectionName", resolved);
            body.put("message", e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    // ============ GET /api/ai/rag/collection-status（索引/加载状态）============

    /**
     * 查询指定 Collection 的索引状态与加载状态。
     *   - 索引已创建：indexExists = true（Milvus 通过 describeIndex 检测；其他存储默认 true）
     *   - 是否加载到内存：isLoaded = true/false（仅 Milvus 实际维护，其他存储默认 true）
     */
    @GetMapping(value = "/collection-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getCollectionStatus(
            @RequestParam(name = "collectionName") String collectionName) {
        Map<String, Object> body = new HashMap<>();
        try {
            // 1) Collection 存在性检查
            if (!vectorStoreProvider.collectionExists(collectionName)) {
                body.put("status", "not_found");
                body.put("collectionName", collectionName);
                body.put("indexExists", false);
                body.put("isLoaded", false);
                body.put("message", "该 Collection 不存在，无法查询索引/加载状态");
                return ResponseEntity.status(404).body(body);
            }

            // 2) 索引/加载状态检测
            // 直接使用 VectorStoreProvider 的 indexExists / isLoaded 方法：
            // Milvus 实现会覆盖这两个方法以返回真实状态；其他实现默认 true。
            boolean indexExists = vectorStoreProvider.indexExists(collectionName);
            boolean isLoaded = vectorStoreProvider.isLoaded(collectionName);

            body.put("status", "ok");
            body.put("collectionName", collectionName);
            body.put("indexExists", indexExists);
            body.put("isLoaded", isLoaded);
            body.put("vectorStore", properties.getRag().getVectorStore());
            log.info("[rag] collection-status：{} → indexExists={}, isLoaded={}",
                    collectionName, indexExists, isLoaded);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("查询 Collection 状态失败（{}）：{}", collectionName, e.getMessage(), e);
            body.put("status", "error");
            body.put("collectionName", collectionName);
            body.put("message", e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    // ============ GET /api/ai/rag/collections & DELETE /api/ai/rag/collection ============

    /**
     * 获取当前向量数据库中所有 Collection 名称列表。
     */
    @GetMapping(value = "/collections", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getCollections() {
        Map<String, Object> body = new HashMap<>();
        try {
            List<String> names = vectorStoreProvider.getCollectionNames();
            body.put("status", "ok");
            body.put("collections", names);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("获取 Collection 列表失败：{}", e.getMessage(), e);
            body.put("status", "error");
            body.put("message", e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    /**
     * 删除指定 Collection（整表删除，不可逆）。前端应提供二次确认。
     */
    @DeleteMapping(value = "/collection", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deleteCollection(
            @RequestParam(name = "collectionName") String collectionName) {
        Map<String, Object> body = new HashMap<>();
        try {
            vectorStoreProvider.dropCollection(collectionName);
            body.put("status", "ok");
            body.put("collectionName", collectionName);
            body.put("message", "Collection 已删除");
            log.info("[rag] 已删除 Collection: {}", collectionName);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("删除 Collection 失败（{}）：{}", collectionName, e.getMessage(), e);
            body.put("status", "error");
            body.put("collectionName", collectionName);
            body.put("message", e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    // ============ POST /api/ai/rag/test-connection ============

    /**
     * 测试向量数据库连接（临时客户端；不使用已有 VectorStoreProvider Bean）。
     *
     * 请求体示例：
     *   {
     *     "vectorStore": "milvus",
     *     "host": "localhost",
     *     "port": 19530,
     *     "database": "postgres",
     *     "username": "postgres",
     *     "password": "postgres",
     *     "apiKey": "..."
     *   }
     *
     * vectorStore 可选值：milvus / qdrant / pgvector / weaviate。
     * 超时统一设置为 5 秒。
     */
    @PostMapping(value = "/test-connection", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> testConnection(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body != null ? body : new LinkedHashMap<>();
        String vectorStore = strVal(payload.get("vectorStore"));
        String host = strVal(payload.get("host"));
        Object portObj = payload.get("port");
        String database = strVal(payload.get("database"));
        String username = strVal(payload.get("username"));
        String password = strVal(payload.get("password"));
        String apiKey = strVal(payload.get("apiKey"));

        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            if (vectorStore == null || vectorStore.isBlank()) {
                resp.put("status", "error");
                resp.put("message", "缺少必填参数：vectorStore");
                return ResponseEntity.badRequest().body(resp);
            }
            if (host == null || host.isBlank()) {
                resp.put("status", "error");
                resp.put("message", "缺少必填参数：host");
                return ResponseEntity.badRequest().body(resp);
            }
            int port = (portObj instanceof Number) ? ((Number) portObj).intValue() : -1;

            switch (vectorStore.trim().toLowerCase()) {
                case "milvus":
                    testMilvus(host, port);
                    break;
                case "qdrant":
                    testQdrant(host, port, apiKey);
                    break;
                case "pgvector":
                case "postgres":
                    testPgvector(host, port, database, username, password);
                    break;
                case "weaviate":
                    testWeaviate(host, port, apiKey);
                    break;
                default:
                    resp.put("status", "error");
                    resp.put("message", "不支持的 vectorStore：" + vectorStore +
                            "（可选：milvus / qdrant / pgvector / weaviate）");
                    return ResponseEntity.badRequest().body(resp);
            }

            resp.put("status", "ok");
            resp.put("vectorStore", vectorStore);
            resp.put("host", host);
            resp.put("message", "连接成功");
            log.info("[rag] 测试连接成功：vectorStore={}, host={}:{}", vectorStore, host, port);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("[rag] 测试连接失败：vectorStore={}, host={}：{}",
                    vectorStore, host, e.getMessage());
            resp.put("status", "error");
            resp.put("vectorStore", vectorStore);
            resp.put("message", e.getMessage());
            return ResponseEntity.status(502).body(resp);
        }
    }

    // ============ 各向量库测试实现 ============

    private void testMilvus(String host, int port) throws Exception {
        if (port <= 0) port = 19530;
        // Milvus 2.5.0 官方 SDK 反射 + TCP 双轨：
        //   ConnectParam.newBuilder().withHost(host).withPort(port).build()
        //   new MilvusServiceClient(connectParam)
        //   client.listCollections(ListCollectionsParam.newBuilder().build())
        // 反射失败（签名不匹配）或其它非网络异常时回退到 TCP 检测；
        // 真正的网络异常（连接超时/拒绝/未知主机）直接抛出以告知调用方。
        boolean sdkOk = false;
        try {
            Class<?> clientCls = Class.forName("io.milvus.client.MilvusServiceClient");
            Class<?> connectCls = Class.forName("io.milvus.param.ConnectParam");
            Object builder = connectCls.getMethod("newBuilder").invoke(null);
            invokeBuilderSetter(connectCls, builder, "withHost", String.class, host);
            invokeBuilderSetter(connectCls, builder, "withPort", int.class, port);
            Object connectParam = connectCls.getMethod("build").invoke(builder);
            Object client = clientCls.getConstructor(connectCls).newInstance(connectParam);
            try {
                Class<?> listParamCls = Class.forName(
                        "io.milvus.param.collection.ListCollectionsParam");
                Object listBuilder = listParamCls.getMethod("newBuilder").invoke(null);
                Object listParam = listParamCls.getMethod("build").invoke(listBuilder);
                Object r = client.getClass().getMethod("listCollections", listParamCls)
                        .invoke(client, listParam);
                if (r == null) {
                    throw new IllegalStateException("listCollections 返回 null");
                }
                try {
                    Object status = r.getClass().getMethod("getStatus").invoke(r);
                    if (status != null) {
                        Integer code = (Integer) status.getClass().getMethod("getCode").invoke(status);
                        if (code != null && code != 0) {
                            throw new IllegalStateException("Milvus 连接异常，状态码：" + code);
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    // 不同版本 R 响应可能没有 getStatus，忽略
                }
                sdkOk = true;
            } finally {
                try { client.getClass().getMethod("close").invoke(client); }
                catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException e) {
            // SDK 不在 classpath，回退 TCP
        } catch (NoSuchMethodException | IllegalArgumentException
                 | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            log.warn("[rag] Milvus SDK 反射调用签名不匹配（{}），回退 TCP 检测",
                    firstMessage(e));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException) {
                throw new RuntimeException("Milvus 连接失败：" + cause.getMessage(), cause);
            }
            log.warn("[rag] Milvus SDK 调用失败（{}），回退 TCP 检测", firstMessage(cause));
        }

        if (!sdkOk) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(host, port),
                        (int) Duration.ofSeconds(5).toMillis());
            }
        }
    }

    /**
     * 优先调用 builderCls#methodName(argType)(arg)，失败时尝试同名 varargs 版本（避免签名差异）。
     * 若仍失败，直接抛出 {@link NoSuchMethodException}。
     */
    private static void invokeBuilderSetter(Class<?> builderCls, Object builder,
                                            String methodName, Class<?> argType, Object arg) throws Exception {
        try {
            builderCls.getMethod(methodName, argType).invoke(builder, arg);
            return;
        } catch (NoSuchMethodException ignored) {
            // 继续查找兼容签名
        }
        // 兼容：String setXxx(String)、Object setXxx(Object)、builder 自身的其他签名
        for (java.lang.reflect.Method m : builderCls.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 1) continue;
            if (pts[0].isAssignableFrom(argType) || argType.isAssignableFrom(pts[0])) {
                m.invoke(builder, arg);
                return;
            }
        }
        throw new NoSuchMethodException(builderCls.getName() + "#" + methodName
                + "(" + argType.getName() + ")");
    }

    private static String firstMessage(Throwable t) {
        if (t == null) return "unknown";
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private void testQdrant(String host, int port, String apiKey) throws Exception {
        if (port <= 0) port = 6334;
        // 优先尝试官方 Qdrant Java SDK（反射，签名随版本会变）。
        // 一旦遇到 ClassNotFoundException / NoSuchMethodException / IllegalArgumentException，
        // 立刻回退到 HTTP GET http://{host}:{port}/healthz 的活度检查。
        boolean sdkOk = false;
        try {
            Class.forName("io.qdrant.client.QdrantClient");
            Class<?> grpcCls = Class.forName("io.qdrant.client.QdrantGrpcClient");
            Object grpcBuilder = grpcCls.getMethod("newBuilder", String.class, int.class, boolean.class)
                    .invoke(null, host, port, false);
            if (apiKey != null && !apiKey.isBlank()) {
                try {
                    grpcCls.getMethod("withApiKey", String.class).invoke(grpcBuilder, apiKey);
                } catch (NoSuchMethodException ignored) {
                    // 某些版本使用不同的鉴权入口，忽略
                }
            }
            Object grpcClient = grpcCls.getMethod("build").invoke(grpcBuilder);
            Object qclient = Class.forName("io.qdrant.client.QdrantClient")
                    .getConstructor(grpcCls).newInstance(grpcClient);
            try {
                java.util.concurrent.Future<?> future = (java.util.concurrent.Future<?>) qclient
                        .getClass().getMethod("healthCheckAsync").invoke(qclient);
                future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                sdkOk = true;
            } finally {
                try { qclient.getClass().getMethod("close").invoke(qclient); }
                catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalArgumentException e) {
            log.warn("[rag] Qdrant SDK 反射调用失败（{}），回退 HTTP 健康检查", e.getMessage());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException) {
                throw new RuntimeException("Qdrant 连接失败：" + cause.getMessage(), cause);
            }
            log.warn("[rag] Qdrant SDK 调用失败（{}），回退 HTTP 健康检查", cause.getMessage());
        }

        if (!sdkOk) {
            // 回退：HTTP GET http://host:port/healthz（5 秒超时，try-with-resources 自动关闭）
            String url = "http://" + host + ":" + port + "/healthz";
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                try {
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    if (apiKey != null && !apiKey.isBlank()) {
                        conn.setRequestProperty("api-key", apiKey);
                    }
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 400) {
                        throw new RuntimeException("Qdrant 返回非成功 HTTP 状态：" + code);
                    }
                } finally {
                    // 关闭输入/错误流，释放连接
                    try (java.io.InputStream is = conn.getErrorStream() != null
                            ? conn.getErrorStream() : conn.getInputStream()) {
                        // 仅消费，不读取内容
                    } catch (Exception ignored) {}
                    conn.disconnect();
                }
            } catch (java.net.MalformedURLException | java.net.ProtocolException e) {
                throw new RuntimeException("Qdrant HTTP 检查参数非法：" + e.getMessage(), e);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Qdrant HTTP 检查失败：" + e.getMessage(), e);
            }
        }
    }

    private void testPgvector(String host, int port, String database,
                               String username, String password) throws Exception {
        if (port <= 0) port = 5432;
        if (database == null || database.isBlank()) database = "postgres";
        if (username == null || username.isBlank()) username = "postgres";
        if (password == null) password = "";
        String jdbc = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        // 注册 PG Driver（如 classpath 内存在）
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException ignored) {
            // 继续；DriverManager 可能仍能自动加载
        }
        java.util.Properties props = new java.util.Properties();
        props.put("user", username);
        props.put("password", password);
        props.put("connectTimeout", "5");
        props.put("loginTimeout", "5");
        try (Connection conn = DriverManager.getConnection(jdbc, props);
             Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
        }
    }

    private void testWeaviate(String host, int port, String apiKey) throws Exception {
        if (port <= 0) port = 8080;
        // Weaviate 客户端反射调用不稳定，且 /v1/meta 受鉴权影响；改为：
        // 1) 首选 HTTP GET http://{host}:{port}/v1/.well-known/live 作为活度检查（200 即 OK）；
        // 2) 若传入 apiKey，再附加 Authorization / X-API-Key，作为可选探测。
        String url = "http://" + host + ":" + port + "/v1/.well-known/live";
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (apiKey != null && !apiKey.isBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setRequestProperty("X-API-Key", apiKey);
                }
                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) {
                    throw new RuntimeException("Weaviate 返回非成功 HTTP 状态：" + code + "（" + url + "）");
                }
            } finally {
                try (java.io.InputStream is = conn.getErrorStream() != null
                        ? conn.getErrorStream() : conn.getInputStream()) {
                    // 仅消费
                } catch (Exception ignored) {}
                conn.disconnect();
            }
        } catch (java.net.MalformedURLException | java.net.ProtocolException e) {
            throw new RuntimeException("Weaviate HTTP 检查参数非法：" + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Weaviate HTTP 检查失败：" + e.getMessage(), e);
        }
    }

    private static String strVal(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * docType/collectionName 二选一：优先 collectionName，否则拼接 {collectionPrefix}_{docType}。
     */
    private String resolveCollectionName(String docType, String collectionName) {
        if (collectionName != null && !collectionName.isBlank()) {
            return collectionName;
        }
        if (docType != null && !docType.isBlank()) {
            return properties.getRag().getCollectionPrefix() + "_" + docType.replace("-", "_");
        }
        return null;
    }
}
