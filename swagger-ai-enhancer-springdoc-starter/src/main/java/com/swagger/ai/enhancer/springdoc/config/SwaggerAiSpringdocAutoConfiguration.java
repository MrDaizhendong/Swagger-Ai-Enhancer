package com.swagger.ai.enhancer.springdoc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swagger.ai.enhancer.springdoc.controller.EnhancedOpenApiController;
import com.swagger.ai.enhancer.springdoc.enhancer.OpenApiEnhancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.service.OpenAPIService;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * springdoc-starter 自动配置类。
 * 负责注册 {@link SpringdocEnhancerProperties}、{@link OpenApiEnhancer} 及（在增强开关开启时）{@link EnhancedOpenApiController}。
 * 前端脚本注入已移至 ui-starter（{@code com.swagger.ai.enhancer.ui.config.SwaggerAiUiAutoConfiguration}），
 * 本模块不再持有 UI 注入逻辑。
 */
@AutoConfiguration
@EnableConfigurationProperties(SpringdocEnhancerProperties.class)
@ConditionalOnClass(SpringDocConfiguration.class)
@ConditionalOnProperty(prefix = "swagger-ai-enhancer.springdoc", name = "enhance-enabled",
        havingValue = "true", matchIfMissing = true)
public class SwaggerAiSpringdocAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SwaggerAiSpringdocAutoConfiguration.class);

    public SwaggerAiSpringdocAutoConfiguration(SpringdocEnhancerProperties properties) {
        log.info("springdoc-starter 自动配置已加载");
        // 启动时对 ai-service-url 做一次轻量可达性检查（调用 /api/ai/health），
        // 便于用户快速发现部署配置问题；若检查失败，后续 API 请求仍会在 OpenApiEnhancer 中降级。
        String aiServiceUrl = properties.getAiServiceUrl();
        if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
            log.warn("swagger-ai-enhancer.springdoc.ai-service-url 未配置：AI 增强功能将始终降级（返回原始 OpenAPI JSON）");
            return;
        }
        // 去除尾部斜杠，避免拼接后出现 http://localhost:8080//api/ai/health
        String trimmed = aiServiceUrl.replaceFirst("/+$", "");
        String probeUrl = trimmed + "/api/ai/health";
        try {
            RestTemplate probe = new RestTemplateBuilder()
                    .setConnectTimeout(Duration.ofSeconds(2))
                    .setReadTimeout(Duration.ofSeconds(2))
                    .build();
            ResponseEntity<String> resp = probe.getForEntity(probeUrl, String.class);
            if (resp.getStatusCode() == HttpStatus.OK || resp.getStatusCode() == HttpStatus.FORBIDDEN || resp.getStatusCode() == HttpStatus.UNAUTHORIZED
                    || resp.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.info("ai-starter 可达性检查完成（URL={}，HTTP={}）：AI 增强管道正常可用。"
                        + " 备注：仅为启动时的连通性提示，后续 API 请求仍会执行完整 /api/ai/complete-all 调用。", probeUrl, resp.getStatusCode());
            } else {
                log.warn("ai-starter 可达性检查完成（URL={}，HTTP={}）：AI 增强功能可能不可用。"
                        + " 请确认 ai-service-url 配置正确，且 ai-starter 已启动。", probeUrl, resp.getStatusCode());
            }
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn("ai-starter 不可达（URL={}，异常={}）：AI 增强功能将在 /v3/api-docs-enhanced 中降级为原始 JSON。"
                    + " 请确认 ai-starter 已启动，或调整 swagger-ai-enhancer.springdoc.ai-service-url。",
                    probeUrl, e.getMessage());
        } catch (Exception e) {
            log.warn("ai-starter 可达性检查异常（URL={}，异常={}:{}）：AI 增强功能将在后续 API 请求中降级为原始 JSON。",
                    probeUrl, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "swaggerAiRestTemplate")
    public RestTemplate swaggerAiRestTemplate(RestTemplateBuilder restTemplateBuilder) {
        return restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenApiEnhancer openApiEnhancer(SpringdocEnhancerProperties properties,
                                           RestTemplate swaggerAiRestTemplate,
                                           ObjectMapper objectMapper) {
        return new OpenApiEnhancer(properties, swaggerAiRestTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "swagger-ai-enhancer.springdoc", name = "enhance-enabled",
            havingValue = "true", matchIfMissing = true)
    public EnhancedOpenApiController enhancedOpenApiController(SpringdocEnhancerProperties properties,
                                                               OpenApiEnhancer openApiEnhancer,
                                                               OpenAPIService openApiService,
                                                               OpenApiWebMvcResource openApiResource,
                                                               ObjectMapper objectMapper) {
        return new EnhancedOpenApiController(properties, openApiEnhancer, openApiService, openApiResource, objectMapper);
    }
}
