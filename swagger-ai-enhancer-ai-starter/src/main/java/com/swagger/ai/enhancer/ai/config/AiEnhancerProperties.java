package com.swagger.ai.enhancer.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ai-starter 配置属性。
 * 前缀：swagger-ai-enhancer.ai
 */
@Data
@ConfigurationProperties(prefix = "swagger-ai-enhancer.ai")
public class AiEnhancerProperties {

    /**
     * 运行模式：embedded 或 client。默认 embedded。
     */
    private String mode = "embedded";

    /**
     * client 模式下的远程 AI 服务地址。
     */
    private String serviceUrl = "http://localhost:8081";

    /**
     * LLM 通用与各提供者配置。
     */
    private LlmConfig llm = new LlmConfig();

    /**
     * RAG 检索增强配置。
     */
    private RagConfig rag = new RagConfig();

    @Data
    public static class LlmConfig {

        /**
         * 当前使用的 LLM 提供者：ollama / openai / deepseek / aliyun-bailian。默认 ollama。
         */
        private String provider = "ollama";

        /**
         * 请求超时时间（秒）。默认 120 秒（云端模型），本地模型建议 300 秒。
         */
        private int timeoutSeconds = 120;

        /**
         * 最大并发调用数。默认 10（云端模型），本地模型建议 2。
         */
        private int maxConcurrency = 10;

        /**
         * 最大生成 Token 数。
         */
        private int maxTokens = 4096;

        /**
         * 生成温度（0.0 ~ 1.0），值越小输出越稳定。
         */
        private double temperature = 0.3;

        /**
         * Ollama 提供者配置。
         */
        private OllamaConfig ollama = new OllamaConfig();

        /**
         * OpenAI 兼容提供者配置（可用于 DeepSeek、Kimi、Llama.cpp 等）。
         */
        private OpenAiCompatibleConfig openaiCompatible = new OpenAiCompatibleConfig();

        /**
         * 阿里云百炼（DashScope）提供者配置。
         */
        private AliyunBailianConfig aliyunBailian = new AliyunBailianConfig();

        /**
         * Embedding 配置（从 llm 独立）。
         */
        private EmbeddingConfig embedding = new EmbeddingConfig();
    }

    @Data
    public static class OllamaConfig {

        /**
         * Ollama API 端点地址。
         */
        private String baseUrl = "http://localhost:11434";

        /**
         * 模型名称。
         */
        private String model = "llama3:latest";

        /**
         * API Key（Ollama 默认可不填）。
         */
        private String apiKey = "";
    }

    @Data
    public static class OpenAiCompatibleConfig {

        /**
         * API Key。
         */
        private String apiKey = "";

        /**
         * API 基础地址。
         */
        private String baseUrl = "https://api.openai.com/v1";

        /**
         * 模型名称（如 gpt-4o / deepseek-chat / moonshot-v1-8k 等）。
         */
        private String model = "gpt-4o";
    }

    @Data
    public static class AliyunBailianConfig {

        /**
         * DashScope API Key。
         */
        private String apiKey = "";

        /**
         * API 基础地址。
         */
        private String baseUrl = "https://dashscope.aliyuncs.com/api/v1";

        /**
         * 模型名称（如 qwen-plus / qwen-turbo 等）。
         */
        private String model = "qwen-plus";
    }

    @Data
    public static class EmbeddingConfig {

        /**
         * Embedding 提供者：ollama / openai / aliyun-bailian。默认 ollama。
         */
        private String provider = "ollama";

        /**
         * Embedding 模型名称。
         */
        private String model = "nomic-embed-text:latest";

        /**
         * Ollama baseUrl（与 OllamaConfig 一致，独立可覆盖）。
         */
        private String ollamaBaseUrl = "http://localhost:11434";

        /**
         * OpenAI 兼容模式下使用的 baseUrl（可选，仅 provider=openai 时生效）。
         */
        private String openaiBaseUrl = "https://api.openai.com/v1";

        /**
         * Embedding API Key（按需）。
         */
        private String apiKey = "";
    }

    @Data
    public static class RagConfig {

        /**
         * 是否启用 RAG 检索增强。
         */
        private boolean enabled = true;

        /**
         * 向量存储类型：milvus / qdrant / pgvector / weaviate。
         */
        private String vectorStore = "milvus";

        /**
         * 检索返回片段数量。
         */
        private int topK = 5;

        /**
         * 相似度阈值，低于此值的片段被过滤。
         */
        private double similarityThreshold = 0.7;

        /**
         * 文本切块大小（字符数）。
         */
        private int chunkSize = 500;

        /**
         * 文本切块重叠字符数（用于上下文连贯）。
         */
        private int chunkOverlap = 50;

        /**
         * Embedding 模型名称（兼容旧配置，已迁移至 llm.embedding.model）。
         */
        private String embeddingModel = "nomic-embed-text:latest";

        /**
         * Embedding 请求基础地址（兼容旧配置）。
         */
        private String embeddingUrl = "http://localhost:11434";

        /**
         * Embedding 向量维度。默认 -1 表示未配置，
         * 需要通过 Swagger UI 的「AI 模型设置」面板配置 Embedding 提供者和模型名称后自动探测。
         */
        private int dimension = -1;

        /**
         * 本地知识库目录路径（兼容旧配置，若配置则视为 docType="default"）。
         */
        @Deprecated
        private String knowledgePath = "";

        /**
         * 多知识库：docType → 本地目录路径。
         * 建议的 docType 包括：
         *   "api"              → API 接口说明
         *   "integration-guide" → 开发者集成指南
         *   "product-doc"       → 产品说明书/非技术文档
         * YAML 示例：
         *   swagger-ai-enhancer.ai.rag.knowledge-paths:
         *     api: /path/to/api-docs
         *     integration-guide: /path/to/guides
         *     product-doc: /path/to/product-specs
         */
        private java.util.Map<String, String> knowledgePaths = new java.util.HashMap<>();

        /**
         * 向量集合前缀：最终 collectionName = {collectionPrefix}_{docType}。
         */
        private String collectionPrefix = "swagger_knowledge";

        /**
         * Milvus 索引参数 nlist（仅用于 createIndex）。
         */
        private int indexNlist = 100;

        /**
         * Milvus 连接配置。
         */
        private MilvusConfig milvus = new MilvusConfig();

        /**
         * Qdrant 连接配置。
         */
        private QdrantConfig qdrant = new QdrantConfig();

        /**
         * PGVector (PostgreSQL) 连接配置。
         */
        private PgVectorConfig pgvector = new PgVectorConfig();

        /**
         * Weaviate 连接配置。
         */
        private WeaviateConfig weaviate = new WeaviateConfig();
    }

    @Data
    public static class MilvusConfig {

        /**
         * Milvus 主机地址。
         */
        private String host = "localhost";

        /**
         * Milvus 端口。
         */
        private int port = 19530;

        /**
         * 向量集合名称。
         */
        private String collectionName = "swagger_knowledge";
    }

    @Data
    public static class QdrantConfig {

        /**
         * Qdrant 主机地址。
         */
        private String host = "localhost";

        /**
         * Qdrant gRPC 端口（默认 6334）。
         */
        private int port = 6334;

        /**
         * API Key（Qdrant Cloud 或启用鉴权的场景使用）。
         */
        private String apiKey = "";

        /**
         * 是否启用 TLS。
         */
        private boolean useTls = false;

        /**
         * 集合名称（默认 swagger_knowledge）。
         */
        private String collectionName = "swagger_knowledge";
    }

    @Data
    public static class PgVectorConfig {

        /**
         * 主机地址。
         */
        private String host = "localhost";

        /**
         * 端口。
         */
        private int port = 5432;

        /**
         * 数据库名。
         */
        private String database = "swagger_ai";

        /**
         * 用户名。
         */
        private String username = "postgres";

        /**
         * 密码。
         */
        private String password = "postgres";

        /**
         * 向量数据表名。
         */
        private String tableName = "vector_store";
    }

    @Data
    public static class WeaviateConfig {

        /**
         * Weaviate 主机地址。
         */
        private String host = "localhost";

        /**
         * Weaviate 端口（默认 8080）。
         */
        private int port = 8080;

        /**
         * 集合（Class）名称，默认 swagger_knowledge。
         */
        private String collectionName = "SwaggerKnowledge";

        /**
         * 可选的 OpenID / API Key。默认为空，仅在启用鉴权时填写。
         */
        private String apiKey = "";
    }
}
