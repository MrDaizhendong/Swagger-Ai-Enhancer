package com.swagger.ai.enhancer.ai.autoconfigure;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.config.MybatisPlusConfig;
import com.swagger.ai.enhancer.ai.controller.AiController;
import com.swagger.ai.enhancer.ai.controller.AiClientForwardController;
import com.swagger.ai.enhancer.ai.controller.AiModelConfigController;
import com.swagger.ai.enhancer.ai.controller.AiRagController;
import com.swagger.ai.enhancer.ai.controller.AiSettingsController;
import com.swagger.ai.enhancer.ai.mapper.AiModelConfigMapper;
import com.swagger.ai.enhancer.ai.mapper.RagConfigMapper;
import com.swagger.ai.enhancer.ai.mapper.RagSyncMetadataMapper;
import com.swagger.ai.enhancer.ai.prompt.PromptTemplateManager;
import com.swagger.ai.enhancer.ai.provider.AliyunBailianLlmProvider;
import com.swagger.ai.enhancer.ai.provider.LlmProvider;
import com.swagger.ai.enhancer.ai.provider.LlmProviderFactory;
import com.swagger.ai.enhancer.ai.provider.OpenAiCompatibleLlmProvider;
import com.swagger.ai.enhancer.ai.provider.OllamaLlmProvider;
import com.swagger.ai.enhancer.ai.rag.DocumentLoader;
import com.swagger.ai.enhancer.ai.rag.EmbeddingService;
import com.swagger.ai.enhancer.ai.rag.RagSyncService;
import com.swagger.ai.enhancer.ai.rag.TextSplitter;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import com.swagger.ai.enhancer.ai.service.AiModelConfigService;
import com.swagger.ai.enhancer.ai.service.RagConfigService;
import com.swagger.ai.enhancer.ai.service.RagMetricsService;
import com.swagger.ai.enhancer.ai.skill.SkillService;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * swagger-ai-enhancer-ai-starter 自动装配。
 * 支持两种模式（通过 swagger-ai-enhancer.ai.mode 配置）：
 *   - embedded（默认）：本地加载 LLM 提供者、RAG 管道、向量数据库
 *   - client：作为轻量级转发网关，将 /api/ai/** 请求转发到远程 service-url
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiEnhancerProperties.class)
@MapperScan("com.swagger.ai.enhancer.ai.mapper")
@Import(MybatisPlusConfig.class)
public class SwaggerAiAiAutoConfiguration {

    // ========== 通用：AI 模型配置服务 / RAG 配置服务 / 控制器 ==========

    @Bean
    @ConditionalOnMissingBean
    public AiModelConfigService aiModelConfigService(AiModelConfigMapper mapper,
                                                     AiEnhancerProperties properties) {
        return new AiModelConfigService(mapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiModelConfigController aiModelConfigController(AiModelConfigService service) {
        return new AiModelConfigController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public RagConfigService ragConfigService(RagConfigMapper mapper,
                                             AiEnhancerProperties properties) {
        return new RagConfigService(mapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiSettingsController aiSettingsController(RagConfigService service) {
        return new AiSettingsController(service);
    }

    // ========== embedded 模式：加载全部 LLM / RAG 组件 ==========

    @ConditionalOnProperty(prefix = "swagger-ai-enhancer.ai",
            name = "mode", havingValue = "embedded", matchIfMissing = true)
    static class EmbeddedConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public DocumentLoader documentLoader() {
            return new DocumentLoader();
        }

        @Bean
        @ConditionalOnMissingBean
        public TextSplitter textSplitter(AiEnhancerProperties properties) {
            AiEnhancerProperties.RagConfig rag = properties.getRag();
            int chunkSize = Math.max(50, rag.getChunkSize());
            int overlap = Math.max(0, Math.min(rag.getChunkOverlap(), chunkSize - 1));
            return new TextSplitter(chunkSize, overlap);
        }

        @Bean
        @ConditionalOnMissingBean
        public EmbeddingService embeddingService(AiEnhancerProperties properties) {
            return new EmbeddingService(properties);
        }

        /**
         * 空兜底向量存储 Bean：
         * 若未检测到任何向量库子模块，则返回空实现，确保应用可正常启动，但 RAG 检索功能不可用。
         * 用户可通过引入对应向量库子模块并设置 swagger-ai-enhancer.ai.rag.vector-store 启用。
         * 仅在 rag.enabled=true 时生效：RAG 未启用时不需要任何 VectorStoreProvider。
         */
        @Bean
        @ConditionalOnMissingBean(VectorStoreProvider.class)
        @ConditionalOnProperty(name = "swagger-ai-enhancer.ai.rag.enabled",
                havingValue = "true", matchIfMissing = false)
        public VectorStoreProvider defaultVectorStoreProvider() {
            log.warn("[embedded] 未检测到任何向量数据库 SDK（Milvus/Qdrant/PGVector/Weaviate），" +
                    "RAG 检索功能将不可用。请按需引入对应子模块并设置 " +
                    "swagger-ai-enhancer.ai.rag.vector-store。");
            return new VectorStoreProvider() {
                @Override
                public void createCollection(String collectionName, int dimension) {
                    // 无操作
                }

                @Override
                public boolean collectionExists(String collectionName) {
                    return false;
                }

                @Override
                public java.util.List<String> getCollectionNames() {
                    return java.util.Collections.emptyList();
                }

                @Override
                public void dropCollection(String collectionName) {
                    // 无操作
                }

                @Override
                public void insert(String collectionName, java.util.List<VectorStoreProvider.VectorDoc> docs) {
                    // 无操作
                }

                @Override
                public java.util.List<VectorStoreProvider.SearchResult> search(String collectionName,
                                                                                 java.util.List<Double> queryVector,
                                                                                 int topK, double minSimilarity) {
                    return java.util.Collections.emptyList();
                }

                @Override
                public void deleteByFile(String collectionName, String filePath) {
                    // 无操作
                }

                @Override
                public void createIndex(String collectionName) {
                    // 无操作
                }

                @Override
                public void loadCollection(String collectionName) {
                    // 无操作
                }

                @Override
                public void releaseCollection(String collectionName) {
                    // 无操作
                }

                @Override
                public boolean indexExists(String collectionName) {
                    return false;
                }

                @Override
                public boolean isLoaded(String collectionName) {
                    return false;
                }
            };
        }

        @Bean
        @ConditionalOnMissingBean
        public LlmProviderFactory llmProviderFactory(AiEnhancerProperties properties) {
            return new LlmProviderFactory(properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public LlmProvider llmProvider(LlmProviderFactory factory) {
            LlmProvider provider = factory.getProvider();
            log.info("[embedded] 装配 LLM 提供者：{}", provider.getProviderName());
            return provider;
        }

        @Bean
        @ConditionalOnProperty(name = "swagger-ai-enhancer.ai.llm.provider",
                havingValue = "ollama", matchIfMissing = true)
        public OllamaLlmProvider ollamaLlmProvider(AiEnhancerProperties properties) {
            return new OllamaLlmProvider(properties);
        }

        @Bean
        @ConditionalOnProperty(name = "swagger-ai-enhancer.ai.llm.provider",
                havingValue = "openai-compatible")
        public OpenAiCompatibleLlmProvider openAiCompatibleLlmProvider(AiEnhancerProperties properties) {
            return new OpenAiCompatibleLlmProvider(properties);
        }

        @Bean
        @ConditionalOnProperty(name = "swagger-ai-enhancer.ai.llm.provider",
                havingValue = "aliyun-bailian")
        public AliyunBailianLlmProvider aliyunBailianLlmProvider(AiEnhancerProperties properties) {
            return new AliyunBailianLlmProvider(properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public PromptTemplateManager promptTemplateManager() {
            return new PromptTemplateManager();
        }

        @Bean
        @ConditionalOnMissingBean
        public RagMetricsService ragMetricsService(RagSyncMetadataMapper metadataMapper) {
            return new RagMetricsService(metadataMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public SkillService skillService(RagConfigService ragConfigService) {
            return new SkillService(ragConfigService);
        }

        @Bean
        @ConditionalOnMissingBean
        public RagSyncService ragSyncService(AiEnhancerProperties properties,
                                             DocumentLoader documentLoader,
                                             TextSplitter textSplitter,
                                             EmbeddingService embeddingService,
                                             VectorStoreProvider vectorStoreProvider,
                                             RagSyncMetadataMapper metadataMapper,
                                             RagConfigService ragConfigService) {
            return new RagSyncService(properties, documentLoader, textSplitter,
                    embeddingService, vectorStoreProvider, metadataMapper, ragConfigService);
        }

        @Bean
        @ConditionalOnMissingBean
        public AiController aiController(AiEnhancerProperties properties,
                                         com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                         LlmProviderFactory llmProviderFactory,
                                         PromptTemplateManager promptTemplateManager,
                                         EmbeddingService embeddingService,
                                         VectorStoreProvider vectorStoreProvider,
                                         RagConfigService ragConfigService,
                                         RagMetricsService ragMetricsService,
                                         SkillService skillService,
                                         AiModelConfigService modelConfigService) {
            return new AiController(objectMapper, llmProviderFactory, promptTemplateManager, properties,
                    embeddingService, vectorStoreProvider, ragConfigService, ragMetricsService, skillService, modelConfigService);
        }

        @Bean
        @ConditionalOnMissingBean
        public AiRagController aiRagController(AiEnhancerProperties properties,
                                               RagSyncService ragSyncService,
                                               VectorStoreProvider vectorStoreProvider,
                                               RagMetricsService ragMetricsService) {
            return new AiRagController(properties, ragSyncService, vectorStoreProvider, ragMetricsService);
        }
    }

    // ========== client 模式：仅暴露转发控制器 ==========

    @ConditionalOnProperty(prefix = "swagger-ai-enhancer.ai",
            name = "mode", havingValue = "client")
    static class ClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AiClientForwardController aiClientForwardController(AiEnhancerProperties properties) {
            log.info("[client] 装配 AI 转发控制器，remote service-url: {}",
                    properties.getServiceUrl());
            return new AiClientForwardController(properties);
        }
    }
}
