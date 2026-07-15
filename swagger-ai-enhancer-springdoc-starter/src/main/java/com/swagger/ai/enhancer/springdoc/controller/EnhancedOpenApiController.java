package com.swagger.ai.enhancer.springdoc.controller;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swagger.ai.enhancer.springdoc.config.SpringdocEnhancerProperties;
import com.swagger.ai.enhancer.springdoc.enhancer.OpenApiEnhancer;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.service.OpenAPIService;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 增强版 OpenAPI 端点控制器。
 * 暴露 {@code /v3/api-docs-enhanced}（可通过 {@link SpringdocEnhancerProperties} 配置），
 * 在 springdoc 生成的原始 OpenAPI JSON 基础上，由 {@link OpenApiEnhancer} 填充 description。
 * 原始 JSON 通过 {@link OpenApiWebMvcResource#openapiJson(HttpServletRequest, String, Locale)} 生成，
 * 从而保证与原生 {@code /v3/api-docs} 的结构（含 paths、components.schemas 等）完全一致。
 */
@RestController
public class EnhancedOpenApiController {

    private static final Logger log = LoggerFactory.getLogger(EnhancedOpenApiController.class);

    private final SpringdocEnhancerProperties properties;
    private final OpenApiEnhancer openApiEnhancer;
    private final OpenAPIService openApiService;
    private final OpenApiWebMvcResource openApiResource;
    private final ObjectMapper objectMapper;

    public EnhancedOpenApiController(SpringdocEnhancerProperties properties,
                                     OpenApiEnhancer openApiEnhancer,
                                     OpenAPIService openApiService,
                                     OpenApiWebMvcResource openApiResource,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.openApiEnhancer = openApiEnhancer;
        this.openApiService = openApiService;
        this.openApiResource = openApiResource;
        this.objectMapper = objectMapper;
    }

    @GetMapping(path = "${swagger-ai-enhancer.springdoc.enhanced-endpoint:/v3/api-docs-enhanced}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> enhancedOpenApi() {
        try {
            HttpServletRequest request = currentRequest();
            // 通过 springdoc 的 OpenApiWebMvcResource 获取与 /v3/api-docs 结构一致的完整 OpenAPI JSON，
            // 其中包含 paths、components.schemas 等由 controller 扫描得到的字段。
            // 注意：第二个参数 apiDocsPath 会用于解析 OpenAPI 规范的 server URL；这里传自身端点即可。
            String apiDocsPath = properties.getEnhancedEndpoint();
            byte[] bytes = openApiResource.openapiJson(request, apiDocsPath, Locale.getDefault());
            String originalJson = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);

            if (!isValidOpenApiJson(originalJson)) {
                log.warn("springdoc 生成的 OpenAPI JSON 结构异常（缺少 paths），回退到 openApiService.build()");
                originalJson = fallbackBuildJson();
            }

            if (!properties.isEnhanceEnabled()) {
                log.debug("增强管道已关闭，返回原始数据");
                return ResponseEntity.ok(originalJson);
            }

            String enhanced = openApiEnhancer.enhance(originalJson);
            return ResponseEntity.ok(enhanced);
        } catch (Exception e) {
            log.error("生成增强版 OpenAPI JSON 失败，降级返回原始数据", e);
            try {
                String originalJson = fallbackBuildJson();
                return ResponseEntity.ok(originalJson);
            } catch (Exception ex) {
                log.error("获取原始 OpenAPI 也失败", ex);
                return ResponseEntity.status(500).body("{\"error\":\"failed to build openapi json\"}");
            }
        }
    }

    /** 从当前请求上下文中获取 HttpServletRequest。 */
    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attr == null) {
            throw new IllegalStateException("无可用的 Servlet 请求上下文，无法获取原始 OpenAPI JSON");
        }
        return attr.getRequest();
    }

    /** 校验 OpenAPI JSON 的顶层结构：必须包含 paths 且 paths 为对象。 */
    private boolean isValidOpenApiJson(String openApiJson) {
        if (openApiJson == null || openApiJson.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(openApiJson);
            JsonNode paths = root.get("paths");
            return paths != null && paths.isObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 兜底：通过 openApiService.build() + ObjectMapper 序列化生成 bare OpenAPI JSON（可能缺失 paths，仅用于降级）。 */
    private String fallbackBuildJson() throws Exception {
        OpenAPI openAPI = openApiService.build(Locale.getDefault());
        return objectMapper.writeValueAsString(openAPI);
    }
}
