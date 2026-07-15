package com.swagger.ai.enhancer.springdoc.enhancer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swagger.ai.enhancer.springdoc.config.SpringdocEnhancerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * OpenAPI JSON 增强管道。
 * 接收原始 OpenAPI JSON，计算 SHA256 作为缓存 key，通过 HTTP 调用 ai-starter 的 /api/ai/complete-all 获取增强结果，
 * 并在内存中缓存一段时间以降低重复调用延迟与成本；调用失败时降级返回原始 JSON；
 * 若增强结果缺失顶层 paths，则视为异常结构，同样降级返回原始 JSON。
 */
public class OpenApiEnhancer {

    private static final Logger log = LoggerFactory.getLogger(OpenApiEnhancer.class);

    private final SpringdocEnhancerProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** 增强结果缓存：key 为原始 JSON 的 SHA256 哈希，value 为增强后的 JSON 文本与存入时间戳。 */
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    public OpenApiEnhancer(SpringdocEnhancerProperties properties, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收原始 OpenAPI JSON，返回增强后的 JSON。
     * - 增强关闭时直接返回原始 JSON
     * - 缓存命中且未过期时返回缓存值
     * - 否则调用 ai-starter /api/ai/complete-all；若返回值缺失 paths 或不是合法 JSON，则降级返回原始 JSON
     */
    public String enhance(String openApiJson) {
        if (openApiJson == null || openApiJson.isEmpty()) {
            return openApiJson;
        }
        if (!properties.isEnhanceEnabled()) {
            log.debug("增强管道已关闭，跳过增强");
            return openApiJson;
        }

        String key = sha256(openApiJson);

        // 缓存命中检查
        if (properties.isCacheEnabled()) {
            CachedResult cached = cache.get(key);
            if (cached != null && !isExpired(cached)) {
                log.info("增强结果命中缓存（key={}），直接返回", key.substring(0, Math.min(12, key.length())));
                return cached.enhancedJson;
            }
        }

        // 调用真实 AI 服务
        String url = buildCompleteAllUrl();
        long start = System.currentTimeMillis();
        log.info("调用 AI 服务：{}（ai-service-url={}）", url, properties.getAiServiceUrl());
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(openApiJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String enhanced = response.getBody();
                long cost = System.currentTimeMillis() - start;
                log.info("AI 调用成功，耗时 {}ms", cost);

                // 结构校验：增强结果必须是合法 JSON，且顶层 paths 必须是对象（非空）
                if (!hasValidPaths(enhanced)) {
                    log.warn("AI 返回的增强结果缺失 paths，降级返回原始 JSON，url={}", url);
                    log.info("已降级为原始 OpenAPI JSON（无 AI 增强描述）");
                    return openApiJson;
                }

                if (properties.isCacheEnabled()) {
                    cache.put(key, new CachedResult(enhanced, System.currentTimeMillis()));
                }
                return enhanced;
            }

            log.error("AI 服务返回非 2xx 状态码：{}，降级返回原始数据", response.getStatusCode());
            log.info("已降级为原始 OpenAPI JSON（无 AI 增强描述）");
            return openApiJson;
        } catch (ResourceAccessException e) {
            long cost = System.currentTimeMillis() - start;
            log.error("AI 服务不可达（{}ms）：{}。请确认 ai-starter 已启动且 swagger-ai-enhancer.springdoc.ai-service-url 配置正确。",
                    cost, e.getMessage());
            log.info("已降级为原始 OpenAPI JSON（无 AI 增强描述）。ai-starter 恢复后，下次请求将自动使用增强结果（可通过增强开关重启/关闭缓存以强制刷新）。");
            return openApiJson;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("AI 增强调用失败（{}ms）：{}——异常类型={}，降级返回原始数据",
                    cost, e.getMessage(), e.getClass().getSimpleName());
            log.info("已降级为原始 OpenAPI JSON（无 AI 增强描述）");
            return openApiJson;
        }
    }

    /**
     * 校验增强结果是否包含合法的顶层 paths 字段。
     * - 必须是合法 JSON
     * - 必须包含 paths 键，且 paths 为对象
     */
    private boolean hasValidPaths(String enhanced) {
        if (enhanced == null || enhanced.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(enhanced);
            JsonNode paths = root.get("paths");
            return paths != null && paths.isObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String buildCompleteAllUrl() {
        String base = properties.getAiServiceUrl();
        if (base == null || base.isEmpty()) {
            base = "http://localhost:8080";
        }
        String trimmed = base;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/api/ai/complete-all";
    }

    private boolean isExpired(CachedResult cached) {
        long ttlMs = Math.max(1L, properties.getCacheTtlHours()) * 60L * 60L * 1000L;
        return System.currentTimeMillis() - cached.createdAt > ttlMs;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 不可用，退化为字符串 hashCode", e);
            return "h" + Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 缓存记录。
     */
    private static final class CachedResult {
        final String enhancedJson;
        final long createdAt;

        CachedResult(String enhancedJson, long createdAt) {
            this.enhancedJson = enhancedJson;
            this.createdAt = createdAt;
        }
    }
}
