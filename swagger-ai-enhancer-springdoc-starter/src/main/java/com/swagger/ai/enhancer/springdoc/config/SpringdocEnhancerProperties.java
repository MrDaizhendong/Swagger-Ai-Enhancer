package com.swagger.ai.enhancer.springdoc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * springdoc-starter AI 增强管道配置属性。
 * 配置前缀：swagger-ai-enhancer.springdoc
 */
@Data
@ConfigurationProperties(prefix = "swagger-ai-enhancer.springdoc")
public class SpringdocEnhancerProperties {

    /**
     * 是否启用 AI 增强管道。关闭后 /v3/api-docs-enhanced 返回与原版一致的数据。
     */
    private boolean enhanceEnabled = true;

    /**
     * 增强版端点的访问路径。
     */
    private String enhancedEndpoint = "/v3/api-docs-enhanced";

    /**
     * 是否在 AI 生成内容上添加标记字段。
     */
    private boolean aiGeneratedMarker = true;

    /**
     * 标记字段名，用于标记 AI 生成的内容。
     */
    private String markerField = "x-ai-generated";

    /**
     * 是否启用 AI 结果缓存。
     */
    private boolean cacheEnabled = true;

    /**
     * 缓存有效期（小时）。
     */
    private int cacheTtlHours = 24;

    /**
     * AI 服务地址（ai-starter）。如：http://localhost:8080
     * <p>
     * 注意：此 URL 应指向 swagger-ai-enhancer-ai-starter 模块的地址。
     * 用于调用 /api/ai/complete-all 进行真实的描述补全。
     * <p>
     * 如果 ai-starter 未启动或不可达，AI 增强功能将静默降级——
     * /v3/api-docs-enhanced 将返回与 /v3/api-docs 一致的原始 OpenAPI JSON，
     * 详见 SwaggerAiSpringdocAutoConfiguration 中的 OpenApiEnhancer 日志。
     */
    private String aiServiceUrl = "http://localhost:8080";
}
