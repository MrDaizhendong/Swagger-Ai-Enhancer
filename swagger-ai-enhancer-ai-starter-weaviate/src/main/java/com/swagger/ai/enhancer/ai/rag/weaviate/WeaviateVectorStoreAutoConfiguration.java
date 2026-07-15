package com.swagger.ai.enhancer.ai.rag.weaviate;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import io.weaviate.client.WeaviateClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Weaviate 向量库自动装配。仅当以下条件同时满足时生效：
 * - ai.mode=embedded（或未设置，默认 embedded）
 * - classpath 中存在 Weaviate 实现类及 Weaviate SDK 核心类
 * - ai.rag.vector-store=weaviate
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "swagger-ai-enhancer.ai",
        name = "mode", havingValue = "embedded", matchIfMissing = true)
@ConditionalOnClass({WeaviateVectorStore.class, WeaviateClient.class})
public class WeaviateVectorStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(VectorStoreProvider.class)
    @ConditionalOnProperty(name = "swagger-ai-enhancer.ai.rag.vector-store",
            havingValue = "weaviate", matchIfMissing = false)
    public VectorStoreProvider weaviateVectorStore(AiEnhancerProperties properties) {
        return new WeaviateVectorStore(properties);
    }
}
