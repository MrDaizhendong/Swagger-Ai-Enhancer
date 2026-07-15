package com.swagger.ai.enhancer.ai.rag.milvus;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import io.milvus.client.MilvusServiceClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Milvus 向量库自动装配。仅当以下条件同时满足时生效：
 * - ai.mode=embedded（或未设置，默认 embedded）
 * - classpath 中存在 Milvus 实现类及 Milvus SDK 核心类
 * - ai.rag.vector-store=milvus
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "swagger-ai-enhancer.ai",
        name = "mode", havingValue = "embedded", matchIfMissing = true)
@ConditionalOnClass({MilvusVectorStore.class, MilvusServiceClient.class})
public class MilvusVectorStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(VectorStoreProvider.class)
    @ConditionalOnProperty(name = "swagger-ai-enhancer.ai.rag.vector-store",
            havingValue = "milvus", matchIfMissing = false)
    public VectorStoreProvider milvusVectorStore(AiEnhancerProperties properties) {
        return new MilvusVectorStore(properties);
    }
}
