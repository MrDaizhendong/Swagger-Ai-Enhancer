package com.swagger.ai.enhancer.ai.rag.qdrant;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import io.qdrant.client.QdrantClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Qdrant 向量库自动装配。仅当以下条件同时满足时生效：
 * - ai.mode=embedded（或未设置，默认 embedded）
 * - classpath 中存在 Qdrant 实现类及 Qdrant SDK 核心类
 * - ai.rag.vector-store=qdrant
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "swagger-ai-enhancer.ai",
        name = "mode", havingValue = "embedded", matchIfMissing = true)
@ConditionalOnClass({QdrantVectorStore.class, io.qdrant.client.QdrantClient.class})
public class QdrantVectorStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(VectorStoreProvider.class)
    @ConditionalOnProperty(name = "swagger-ai-enhancer.ai.rag.vector-store",
            havingValue = "qdrant", matchIfMissing = false)
    public VectorStoreProvider qdrantVectorStore(AiEnhancerProperties properties) {
        return new QdrantVectorStore(properties);
    }
}
