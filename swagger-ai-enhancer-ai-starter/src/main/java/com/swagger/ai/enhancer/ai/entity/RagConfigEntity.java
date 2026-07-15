package com.swagger.ai.enhancer.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 配置实体，对应表 rag_config（按文档类型独立一行）。
 */
@Data
@TableName("rag_config")
public class RagConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("doc_type")
    private String docType;

    @TableField("knowledge_path")
    private String knowledgePath;

    @TableField("vector_store")
    private String vectorStore;

    @TableField("collection_name")
    private String collectionName;

    @TableField("milvus_host")
    private String milvusHost;

    @TableField("milvus_port")
    private Integer milvusPort;

    @TableField("qdrant_host")
    private String qdrantHost;

    @TableField("qdrant_port")
    private Integer qdrantPort;

    @TableField("pgvector_host")
    private String pgvectorHost;

    @TableField("pgvector_port")
    private Integer pgvectorPort;

    @TableField("pgvector_database")
    private String pgvectorDatabase;

    @TableField("weaviate_host")
    private String weaviateHost;

    @TableField("weaviate_port")
    private Integer weaviatePort;

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("chunk_size")
    private Integer chunkSize;

    @TableField("chunk_overlap")
    private Integer chunkOverlap;

    @TableField("top_k")
    private Integer topK;

    @TableField("similarity_threshold")
    private Double similarityThreshold;

    @TableField("skill_paths")
    private String skillPaths;

    @TableField("enable_refine")
    private Boolean enableRefine;

    @TableField("refine_use_detailed")
    private Boolean refineUseDetailed;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
