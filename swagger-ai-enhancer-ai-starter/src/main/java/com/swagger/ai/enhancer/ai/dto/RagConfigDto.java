package com.swagger.ai.enhancer.ai.dto;

import lombok.Data;

/**
 * 单个文档类型的 RAG 配置 DTO。
 *
 * 字段与 rag_config 表一一对应；
 * 不含敏感字段（密码/API Key），敏感信息在 application.yml / 环境变量中管理。
 */
@Data
public class RagConfigDto {

    private String docType;

    private String knowledgePath;

    private String vectorStore;

    private String collectionName;

    private String milvusHost;

    private Integer milvusPort;

    private String qdrantHost;

    private Integer qdrantPort;

    private String pgvectorHost;

    private Integer pgvectorPort;

    private String pgvectorDatabase;

    private String weaviateHost;

    private Integer weaviatePort;

    private String embeddingModel;

    private Integer chunkSize;

    private Integer chunkOverlap;

    private Integer topK;

    private Double similarityThreshold;

    /**
     * Skill 文档目录路径。非空时，SkillService 会扫描该目录下的 {@code .md} 文件，
     * 作为 System Prompt 的一部分注入 LLM。为空时回退 classpath 默认 Skill。
     */
    private String skillPaths;

    /**
     * 是否启用 Refine 润色。null 或 true 时执行 Refine，false 时跳过。
     */
    private Boolean enableRefine;

    /**
     * 是否基于详细描述润色。true 时使用接口完整描述，false 时使用接口摘要。
     */
    private Boolean refineUseDetailed;
}
