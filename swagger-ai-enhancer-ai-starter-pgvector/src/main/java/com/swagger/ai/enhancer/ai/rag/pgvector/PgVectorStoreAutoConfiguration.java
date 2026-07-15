package com.swagger.ai.enhancer.ai.rag.pgvector;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import org.postgresql.Driver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * PGVector 向量库自动装配。仅当以下条件同时满足时生效：
 * - ai.mode=embedded（或未设置，默认 embedded）
 * - classpath 中存在 PGVector 实现类及 PostgreSQL 驱动
 * - ai.rag.vector-store=pgvector
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "swagger-ai-enhancer.ai",
        name = "mode", havingValue = "embedded", matchIfMissing = true)
@ConditionalOnClass({PgVectorStore.class, Driver.class})
public class PgVectorStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(VectorStoreProvider.class)
    @ConditionalOnProperty(name = "swagger-ai-enhancer.ai.rag.vector-store",
            havingValue = "pgvector", matchIfMissing = false)
    public VectorStoreProvider pgVectorStore(AiEnhancerProperties properties) {
        return new PgVectorStore(properties);
    }
}
