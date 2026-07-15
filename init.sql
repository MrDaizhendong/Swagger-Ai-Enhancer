-- ============================================================
-- swagger-ai-enhancer 数据库初始化脚本
-- 运行方式（在 WSL2 Ubuntu 或本机 MySQL 客户端执行）：
--   mysql -h localhost -u root -p < init.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS swagger_ai_enhancer
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE swagger_ai_enhancer;

-- --------------------------------------------------------
-- 清理旧表
-- --------------------------------------------------------
DROP TABLE IF EXISTS user_settings;

-- --------------------------------------------------------
-- rag_config：每种文档类型独立一行 RAG 配置
-- doc_type 取值：
--   product-doc         产品说明书
--   requirement-doc     需求文档
--   delivery-doc        交付文档
--   testcase-doc        测试用例文档
--   integration-guide   集成指南
--   api                 API 接口说明
-- vector_store 取值：milvus / qdrant / pgvector / weaviate
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_config (
    id                      BIGINT        AUTO_INCREMENT PRIMARY KEY,
    doc_type                VARCHAR(50)   NOT NULL
                                          COMMENT '文档类型：product-doc / requirement-doc / delivery-doc / testcase-doc / integration-guide / api',
    knowledge_path          VARCHAR(500)  DEFAULT ''
                                          COMMENT '知识库根路径',
    vector_store            VARCHAR(20)   DEFAULT 'milvus'
                                          COMMENT '向量数据库：milvus / qdrant / pgvector / weaviate',
    collection_name         VARCHAR(100)  DEFAULT ''
                                          COMMENT 'Collection 名称；为空时系统按 {collectionPrefix}_{docType} 自动生成',
    milvus_host             VARCHAR(100)  DEFAULT 'localhost',
    milvus_port             INT           DEFAULT 19530,
    qdrant_host             VARCHAR(100)  DEFAULT 'localhost',
    qdrant_port             INT           DEFAULT 6334,
    pgvector_host           VARCHAR(100)  DEFAULT 'localhost',
    pgvector_port           INT           DEFAULT 5432,
    pgvector_database       VARCHAR(100)  DEFAULT 'swagger_ai',
    weaviate_host           VARCHAR(100)  DEFAULT 'localhost',
    weaviate_port           INT           DEFAULT 8080,
    embedding_model         VARCHAR(100)  DEFAULT 'nomic-embed-text:latest'
                                          COMMENT 'Embedding 模型名',
    chunk_size              INT           DEFAULT 500,
    chunk_overlap           INT           DEFAULT 50,
    top_k                   INT           DEFAULT 5
                                          COMMENT '检索返回片段数量',
    similarity_threshold    DOUBLE        DEFAULT 0.7
                                          COMMENT '相似度阈值，低于此值的片段被过滤',
    created_at              TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rag_config_doc_type (doc_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT ='RAG配置表（按文档类型独立配置）';

CREATE INDEX idx_rag_config_vector_store ON rag_config (vector_store);

ALTER TABLE rag_config
ADD COLUMN skill_paths TEXT NULL
COMMENT '自定义Skill文档目录路径。用户可配置多个目录，用逗号或JSON数组存储。为空时使用系统默认Skill文档。例如: /home/user/skills/product-doc 或 E:\skills\product-doc';

ALTER TABLE rag_config
ADD COLUMN enable_refine TINYINT(1) DEFAULT 1
COMMENT '是否启用文档润色，1=启用，0=关闭';

ALTER TABLE rag_config
ADD COLUMN refine_use_detailed TINYINT(1) DEFAULT 0
COMMENT '润色时是否使用详细描述，1=使用阶段三生成结果，0=仅使用阶段二精简结果';

-- --------------------------------------------------------
-- rag_sync_metadata：RAG 增量同步元数据
-- 每个 docType + filePath 唯一一条记录，存储 SHA-256 哈希、chunk 数量与最后同步时间
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_sync_metadata (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    doc_type        VARCHAR(50)   NOT NULL
                                  COMMENT '文档类型，逻辑关联 rag_config.doc_type',
    file_path       VARCHAR(500)  NOT NULL
                                  COMMENT '文件路径（相对于知识库根目录的绝对路径或规范化路径）',
    file_hash       VARCHAR(64)   NOT NULL
                                  COMMENT '文件 SHA-256 哈希值',
    chunk_count     INT           DEFAULT 0
                                  COMMENT '该文件切块数量',
    last_synced_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rag_sync_doc_type_file_path (doc_type, file_path)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT ='RAG增量同步元数据表';

CREATE INDEX idx_rag_sync_doc_type ON rag_sync_metadata (doc_type);


-- AI 模型配置表（用户可在前端面板中设置并持久化）
CREATE TABLE IF NOT EXISTS ai_model_config (
    id                  BIGINT       AUTO_INCREMENT PRIMARY KEY,
    model_type          VARCHAR(20)  NOT NULL DEFAULT 'cloud' COMMENT '模型类型：cloud / local',
    provider            VARCHAR(50)  NOT NULL DEFAULT 'openai' COMMENT '提供商标识：openai / deepseek / glm / kimi / aliyun-bailian / ollama / llama.cpp / vllm',
    api_key             VARCHAR(255) DEFAULT '' COMMENT 'API Key（存储时建议加密，前端显示脱敏）',
    base_url            VARCHAR(500) DEFAULT '' COMMENT 'API 基础地址',
    model_name          VARCHAR(100) DEFAULT '' COMMENT '模型名称',
    temperature         DOUBLE       DEFAULT 0.7 COMMENT '生成温度 0-2',
    max_tokens          INT          DEFAULT 4096 COMMENT '最大生成 Token 数',
    timeout_seconds     INT          DEFAULT 120 COMMENT '请求超时（秒）',
    top_p               DOUBLE       DEFAULT 1.0 COMMENT '核采样参数',
    frequency_penalty   DOUBLE       DEFAULT 0.0 COMMENT '频率惩罚',
    presence_penalty    DOUBLE       DEFAULT 0.0 COMMENT '存在惩罚',
    is_enabled          TINYINT(1)   DEFAULT 1 COMMENT '是否启用该配置',
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_model_provider (provider)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI模型配置表';

-- Embedding 配置扩展（2026-07-06 追加；已有数据库需单独执行）
ALTER TABLE ai_model_config
    ADD COLUMN embedding_provider VARCHAR(50) DEFAULT 'ollama' COMMENT 'Embedding 提供者：ollama / openai / aliyun-bailian',
    ADD COLUMN embedding_model VARCHAR(100) DEFAULT 'nomic-embed-text:latest' COMMENT 'Embedding 模型名称';

ALTER TABLE ai_model_config ADD COLUMN embedding_dimension INT DEFAULT -1 COMMENT '探测得到的实际向量维度，-1表示未探测';

-- 模型基础能力
ALTER TABLE ai_model_config
ADD COLUMN max_context_tokens INT DEFAULT 0
COMMENT '模型最大上下文 token 数，0 表示未探测或使用默认值（128K）';

ALTER TABLE ai_model_config
ADD COLUMN max_output_tokens INT DEFAULT 0
COMMENT '模型最大输出 token 数，0 表示未探测或使用默认值（4096）';

-- 模型标识
ALTER TABLE ai_model_config
ADD COLUMN model_family VARCHAR(50) DEFAULT ''
COMMENT '模型家族：llama / qwen / gpt / claude / deepseek / glm / other';

-- 量化与资源
ALTER TABLE ai_model_config
ADD COLUMN quantization VARCHAR(20) DEFAULT ''
COMMENT '量化级别：q4_0 / q4_K_M / q8_0 / f16 / none（云端模型填 none）';

ALTER TABLE ai_model_config
ADD COLUMN model_size_gb DECIMAL(5,2) DEFAULT 0.00
COMMENT '模型文件大小（GB），0 表示未知';

-- 定价信息（仅云端模型有意义）
ALTER TABLE ai_model_config
ADD COLUMN prompt_price_per_1k_tokens DECIMAL(10,6) DEFAULT 0.000000
COMMENT '输入价格（每1000 token），单位：美元';

ALTER TABLE ai_model_config
ADD COLUMN completion_price_per_1k_tokens DECIMAL(10,6) DEFAULT 0.000000
COMMENT '输出价格（每1000 token），单位：美元';

-- 其他元信息
ALTER TABLE ai_model_config
ADD COLUMN knowledge_cutoff_date DATE DEFAULT NULL
COMMENT '模型知识截止日期，NULL 表示未知';

ALTER TABLE ai_model_config
ADD COLUMN capabilities TEXT NULL
COMMENT '模型能力标签，JSON 数组格式，如 ["chat","embedding","vision","function_calling"]';

-- 初始化一条默认配置（与 application.yml 保持一致）
INSERT INTO ai_model_config (model_type, provider, api_key, base_url, model_name, temperature, max_tokens, timeout_seconds)
VALUES ('local', 'ollama', '', 'http://localhost:11434', 'llama3:latest', 0.3, 4096, 120)
ON DUPLICATE KEY UPDATE updated_at = NOW();