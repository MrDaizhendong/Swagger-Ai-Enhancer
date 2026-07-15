package com.swagger.ai.enhancer.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.dto.RagConfigDto;
import com.swagger.ai.enhancer.ai.entity.RagConfigEntity;
import com.swagger.ai.enhancer.ai.mapper.RagConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 配置服务（按文档类型独立存储）。
 *
 * 核心职责：
 *   - loadAllConfigs()                       — 读取数据库全部配置，按 docType 分组返回 DTO
 *   - getConfigOrDefault(docType)             — 读取某个 docType 的配置；数据库为空时回退 YAML 默认值
 *   - saveConfig(docType, dto)                — 按 docType upsert 一条记录
 *   - loadAllAndApply()                       — @PostConstruct 阶段：将数据库配置覆盖到 AiEnhancerProperties.rag
 *
 * docType 的标准取值：
 *   product-doc / requirement-doc / delivery-doc / testcase-doc / integration-guide / api
 */
@Slf4j
public class RagConfigService {

    /** 规范文档类型集合：与项目中其它位置保持一致 */
    public static final List<String> STANDARD_DOC_TYPES = List.of(
            "product-doc",
            "requirement-doc",
            "delivery-doc",
            "testcase-doc",
            "integration-guide",
            "api"
    );

    private final RagConfigMapper mapper;
    private final AiEnhancerProperties properties;

    @Autowired
    public RagConfigService(RagConfigMapper mapper, AiEnhancerProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 启动时从数据库加载所有配置，并覆盖 AiEnhancerProperties.rag 字段 */
    @PostConstruct
    public void loadAllAndApply() {
        Map<String, RagConfigDto> configs;
        try {
            configs = loadAllConfigs();
        } catch (Exception e) {
            log.warn("[rag-config] 启动阶段读取数据库失败（可能尚未初始化），继续使用 YAML 默认值: {}",
                    e.getMessage());
            return;
        }

        if (configs == null || configs.isEmpty()) {
            log.info("[rag-config] 数据库为空，继续使用 YAML 默认配置");
            // ===== 即便 DB 为空，检查向量维度是否已配置 =====
            if (properties.getRag().getDimension() <= 0) {
                log.warn("[rag-config] Embedding 向量维度未配置（dimension=-1）。请先到 Swagger UI 的「AI 模型设置」面板保存 Embedding 配置，系统将自动探测模型维度。");
            }
            return;
        }

        // 逐条应用到 AiEnhancerProperties
        for (Map.Entry<String, RagConfigDto> e : configs.entrySet()) {
            try {
                applyToProperties(e.getKey(), e.getValue());
            } catch (Exception ex) {
                log.warn("[rag-config] 应用 docType={} 的配置失败: {}", e.getKey(), ex.getMessage());
            }
        }
        // ===== 应用完所有配置后再检查一次维度 =====
        if (properties.getRag().getDimension() <= 0) {
            log.warn("[rag-config] Embedding 向量维度未配置（dimension=-1）。请先到 Swagger UI 的「AI 模型设置」面板保存 Embedding 配置，系统将自动探测模型维度。");
        } else {
            log.info("[rag-config] 当前 Embedding 向量维度：{}", properties.getRag().getDimension());
        }
        log.info("[rag-config] 已从数据库加载 {} 个文档类型的 RAG 配置", configs.size());
    }

    /** 读取数据库全部配置，按 docType 分组返回 */
    public Map<String, RagConfigDto> loadAllConfigs() {
        List<RagConfigEntity> entities = mapper.selectList(null);
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        Map<String, RagConfigDto> map = new LinkedHashMap<>();
        for (RagConfigEntity entity : entities) {
            map.put(entity.getDocType(), toDto(entity));
        }
        return map;
    }

    /** 读取某文档类型的配置：优先数据库，否则回退 YAML 默认值 */
    public RagConfigDto getConfigOrDefault(String docType) {
        QueryWrapper<RagConfigEntity> qw = new QueryWrapper<>();
        qw.eq("doc_type", docType);
        RagConfigEntity entity = mapper.selectOne(qw);
        if (entity != null) {
            return toDto(entity);
        }
        return buildDefaultDto(docType);
    }

    /** 按 docType 保存配置：先查 → 对比 → 条件 update / insert；无变化跳过更新 */
    public void saveConfig(String docType, RagConfigDto dto) {
        if (docType == null || docType.isBlank()) {
            throw new IllegalArgumentException("docType 不能为空");
        }
        if (dto == null) {
            throw new IllegalArgumentException("dto 不能为空");
        }
        // 1) 以 doc_type 为唯一索引查询是否存在；异常时按"不存在"降级处理
        RagConfigEntity existing = null;
        try {
            QueryWrapper<RagConfigEntity> qw = new QueryWrapper<>();
            qw.eq("doc_type", docType);
            existing = mapper.selectOne(qw);
        } catch (Exception e) {
            log.warn("[rag-config] 查询 docType={} 的配置失败，按不存在处理: {}",
                    docType, e.getMessage());
            existing = null;
        }

        RagConfigEntity entity = toEntity(docType, dto);

        if (existing != null) {
            // 2) 逐字段对比新旧数据；无变化则跳过 UPDATE，仍然应用到 properties
            boolean dataChanged = isRagConfigChanged(existing, entity);
            if (!dataChanged) {
                log.info("[rag-config] docType={} 配置无变化，跳过更新", docType);
                applyToProperties(docType, dto);
                return;
            }
            // 3) 复用 id 执行 updateById，异常捕获
            entity.setId(existing.getId());
            try {
                mapper.updateById(entity);
                log.info("[rag-config] docType={} 更新成功", docType);
            } catch (Exception e) {
                log.error("[rag-config] docType={} 更新失败: {}", docType, e.getMessage());
                throw new RuntimeException("RAG 配置更新失败", e);
            }
        } else {
            // 4) 不存在则 insert，异常捕获
            entity.setDocType(docType);
            try {
                mapper.insert(entity);
                log.info("[rag-config] docType={} 新增成功", docType);
            } catch (Exception e) {
                log.error("[rag-config] docType={} 新增失败: {}", docType, e.getMessage());
                throw new RuntimeException("RAG 配置保存失败", e);
            }
        }
        // 立即应用到 properties，使新配置在重启前即可生效
        applyToProperties(docType, dto);
    }

    /** 逐字段对比新旧 RAG 配置（排除 id、createdAt、updatedAt）。任意不同返回 true。 */
    private boolean isRagConfigChanged(RagConfigEntity oldE, RagConfigEntity newE) {
        if (oldE == null && newE == null) return false;
        if (oldE == null || newE == null) return true;

        // 字符串字段：null / 空白统一为 "" 后比较
        if (!java.util.Objects.equals(trim(oldE.getVectorStore()), trim(newE.getVectorStore())))
            return true;
        if (!java.util.Objects.equals(trim(oldE.getKnowledgePath()), trim(newE.getKnowledgePath())))
            return true;
        // 对比 skillPaths：null / 空白 视为相同，避免每次保存都被误判为变更
        if (!java.util.Objects.equals(trim(oldE.getSkillPaths()), trim(newE.getSkillPaths())))
            return true;
        if (!java.util.Objects.equals(trim(oldE.getCollectionName()), trim(newE.getCollectionName())))
            return true;
        if (!java.util.Objects.equals(trim(oldE.getMilvusHost()), trim(newE.getMilvusHost())))
            return true;
        if (!java.util.Objects.equals(oldE.getMilvusPort(), newE.getMilvusPort()))
            return true;
        if (!java.util.Objects.equals(trim(oldE.getQdrantHost()), trim(newE.getQdrantHost())))
            return true;
        if (!java.util.Objects.equals(oldE.getQdrantPort(), newE.getQdrantPort()))
            return true;
        if (!java.util.Objects.equals(trim(oldE.getPgvectorHost()), trim(newE.getPgvectorHost())))
            return true;
        if (!java.util.Objects.equals(oldE.getPgvectorPort(), newE.getPgvectorPort()))
            return true;
        if (!java.util.Objects.equals(trim(oldE.getPgvectorDatabase()), trim(newE.getPgvectorDatabase())))
            return true;
        if (!java.util.Objects.equals(trim(oldE.getWeaviateHost()), trim(newE.getWeaviateHost())))
            return true;
        if (!java.util.Objects.equals(oldE.getWeaviatePort(), newE.getWeaviatePort()))
            return true;
        if (!java.util.Objects.equals(trim(oldE.getEmbeddingModel()), trim(newE.getEmbeddingModel())))
            return true;
        // 数值字段：直接 Objects.equals（容忍 null）
        if (!java.util.Objects.equals(oldE.getChunkSize(), newE.getChunkSize()))
            return true;
        if (!java.util.Objects.equals(oldE.getChunkOverlap(), newE.getChunkOverlap()))
            return true;
        if (!java.util.Objects.equals(oldE.getTopK(), newE.getTopK()))
            return true;
        if (!java.util.Objects.equals(oldE.getSimilarityThreshold(), newE.getSimilarityThreshold()))
            return true;
        if (!java.util.Objects.equals(oldE.getEnableRefine(), newE.getEnableRefine()))
            return true;
        if (!java.util.Objects.equals(oldE.getRefineUseDetailed(), newE.getRefineUseDetailed()))
            return true;
        return false;
    }

    /** null / 空白字符串统一为 ""，避免 "  " 与 "" 被误判为不同 */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ====================== 内部工具方法 ======================

    private RagConfigDto toDto(RagConfigEntity entity) {
        RagConfigDto dto = new RagConfigDto();
        dto.setDocType(entity.getDocType());
        dto.setKnowledgePath(entity.getKnowledgePath());
        dto.setSkillPaths(entity.getSkillPaths());
        dto.setVectorStore(entity.getVectorStore());
        dto.setCollectionName(entity.getCollectionName());
        dto.setMilvusHost(entity.getMilvusHost());
        dto.setMilvusPort(entity.getMilvusPort());
        dto.setQdrantHost(entity.getQdrantHost());
        dto.setQdrantPort(entity.getQdrantPort());
        dto.setPgvectorHost(entity.getPgvectorHost());
        dto.setPgvectorPort(entity.getPgvectorPort());
        dto.setPgvectorDatabase(entity.getPgvectorDatabase());
        dto.setWeaviateHost(entity.getWeaviateHost());
        dto.setWeaviatePort(entity.getWeaviatePort());
        dto.setEmbeddingModel(entity.getEmbeddingModel());
        dto.setChunkSize(entity.getChunkSize());
        dto.setChunkOverlap(entity.getChunkOverlap());
        dto.setTopK(entity.getTopK());
        dto.setSimilarityThreshold(entity.getSimilarityThreshold());
        dto.setEnableRefine(entity.getEnableRefine());
        dto.setRefineUseDetailed(entity.getRefineUseDetailed());
        return dto;
    }

    private RagConfigEntity toEntity(String docType, RagConfigDto dto) {
        RagConfigEntity entity = new RagConfigEntity();
        entity.setDocType(docType);
        entity.setKnowledgePath(dto.getKnowledgePath());
        entity.setSkillPaths(dto.getSkillPaths());
        entity.setVectorStore(dto.getVectorStore());
        entity.setCollectionName(dto.getCollectionName());
        entity.setMilvusHost(dto.getMilvusHost());
        entity.setMilvusPort(dto.getMilvusPort());
        entity.setQdrantHost(dto.getQdrantHost());
        entity.setQdrantPort(dto.getQdrantPort());
        entity.setPgvectorHost(dto.getPgvectorHost());
        entity.setPgvectorPort(dto.getPgvectorPort());
        entity.setPgvectorDatabase(dto.getPgvectorDatabase());
        entity.setWeaviateHost(dto.getWeaviateHost());
        entity.setWeaviatePort(dto.getWeaviatePort());
        entity.setEmbeddingModel(dto.getEmbeddingModel());
        entity.setChunkSize(dto.getChunkSize());
        entity.setChunkOverlap(dto.getChunkOverlap());
        entity.setTopK(dto.getTopK());
        entity.setSimilarityThreshold(dto.getSimilarityThreshold());
        entity.setEnableRefine(dto.getEnableRefine());
        entity.setRefineUseDetailed(dto.getRefineUseDetailed());
        return entity;
    }

    /** 从 AiEnhancerProperties 构建某 docType 的默认 DTO */
    private RagConfigDto buildDefaultDto(String docType) {
        AiEnhancerProperties.RagConfig rag = properties.getRag();
        RagConfigDto dto = new RagConfigDto();
        dto.setDocType(docType);
        // knowledgePaths 按 docType 解析；若未配置则回退全局 knowledgePath
        Map<String, String> knowledgePaths = rag.getKnowledgePaths();
        if (knowledgePaths != null && knowledgePaths.containsKey(docType)) {
            dto.setKnowledgePath(knowledgePaths.get(docType));
        } else {
            dto.setKnowledgePath(rag.getKnowledgePath());
        }
        dto.setVectorStore(rag.getVectorStore());
        dto.setCollectionName(rag.getCollectionPrefix() + "_" + docType.replace("-", "_"));
        dto.setMilvusHost(rag.getMilvus() != null ? rag.getMilvus().getHost() : null);
        dto.setMilvusPort(rag.getMilvus() != null ? rag.getMilvus().getPort() : null);
        dto.setQdrantHost(rag.getQdrant() != null ? rag.getQdrant().getHost() : null);
        dto.setQdrantPort(rag.getQdrant() != null ? rag.getQdrant().getPort() : null);
        dto.setPgvectorHost(rag.getPgvector() != null ? rag.getPgvector().getHost() : null);
        dto.setPgvectorPort(rag.getPgvector() != null ? rag.getPgvector().getPort() : null);
        dto.setPgvectorDatabase(rag.getPgvector() != null ? rag.getPgvector().getDatabase() : null);
        // weaviate 尚未在 AiEnhancerProperties 定义，保持 null（由前端或 dto 默认值填充）
        dto.setEmbeddingModel(rag.getEmbeddingModel());
        dto.setChunkSize(rag.getChunkSize());
        dto.setChunkOverlap(rag.getChunkOverlap());
        dto.setTopK(rag.getTopK());
        dto.setSimilarityThreshold(rag.getSimilarityThreshold());
        dto.setEnableRefine(true);
        dto.setRefineUseDetailed(false);
        return dto;
    }

    /** 将数据库加载的 DTO 覆盖到 AiEnhancerProperties。当前仅覆盖 RAG 通用字段。 */
    private void applyToProperties(String docType, RagConfigDto dto) {
        if (dto == null) return;
        AiEnhancerProperties.RagConfig rag = properties.getRag();
        // 1) 将 knowledgePaths[docType] 更新
        if (rag.getKnowledgePaths() == null) {
            rag.setKnowledgePaths(new LinkedHashMap<>());
        }
        if (dto.getKnowledgePath() != null) {
            rag.getKnowledgePaths().put(docType, dto.getKnowledgePath());
        }
        // 2) 通用 RAG 参数（由第一个有值的 docType 覆盖；也可由前端单设）
        if (dto.getVectorStore() != null) {
            rag.setVectorStore(dto.getVectorStore());
        }
        if (dto.getEmbeddingModel() != null) {
            rag.setEmbeddingModel(dto.getEmbeddingModel());
        }
        if (dto.getChunkSize() != null) {
            rag.setChunkSize(dto.getChunkSize());
        }
        if (dto.getChunkOverlap() != null) {
            rag.setChunkOverlap(dto.getChunkOverlap());
        }
        if (dto.getTopK() != null) {
            rag.setTopK(dto.getTopK());
        }
        if (dto.getSimilarityThreshold() != null) {
            rag.setSimilarityThreshold(dto.getSimilarityThreshold());
        }
        // 3) 特定向量数据库连接信息
        if (rag.getMilvus() != null) {
            if (dto.getMilvusHost() != null) rag.getMilvus().setHost(dto.getMilvusHost());
            if (dto.getMilvusPort() != null) rag.getMilvus().setPort(dto.getMilvusPort());
        }
        if (rag.getQdrant() != null) {
            if (dto.getQdrantHost() != null) rag.getQdrant().setHost(dto.getQdrantHost());
            if (dto.getQdrantPort() != null) rag.getQdrant().setPort(dto.getQdrantPort());
        }
        if (rag.getPgvector() != null) {
            if (dto.getPgvectorHost() != null) rag.getPgvector().setHost(dto.getPgvectorHost());
            if (dto.getPgvectorPort() != null) rag.getPgvector().setPort(dto.getPgvectorPort());
            if (dto.getPgvectorDatabase() != null) rag.getPgvector().setDatabase(dto.getPgvectorDatabase());
        }
    }
}
