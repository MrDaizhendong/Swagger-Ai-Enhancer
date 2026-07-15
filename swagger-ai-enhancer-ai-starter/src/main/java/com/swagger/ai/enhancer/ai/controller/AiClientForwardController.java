package com.swagger.ai.enhancer.ai.controller;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * ai-starter client 模式转发控制器。
 * 将 /api/ai/** 请求原样转发到远程服务（swagger-ai-enhancer.ai.service-url）。
 * 不做任何业务处理，不加载 LLM / Embedding / 向量数据库。
 */
@Slf4j
@RestController
@RequestMapping
@ConditionalOnProperty(prefix = "swagger-ai-enhancer.ai", name = "mode", havingValue = "client")
public class AiClientForwardController {

    private final AiEnhancerProperties properties;
    private final RestTemplate restTemplate;

    public AiClientForwardController(AiEnhancerProperties properties) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
    }

    // ========== AiController 对应端点 ==========

    @PostMapping(value = "/api/ai/complete-one", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardCompleteOne(@RequestBody(required = false) String body) {
        return doForward("/api/ai/complete-one", HttpMethod.POST, body);
    }

    @PostMapping(value = "/api/ai/complete-all", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardCompleteAll(@RequestBody(required = false) String body) {
        return doForward("/api/ai/complete-all", HttpMethod.POST, body);
    }

    @GetMapping(value = "/api/ai/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardAiHealth() {
        return doForward("/api/ai/health", HttpMethod.GET, null);
    }

    @PostMapping(value = "/api/ai/generate-guide", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardGenerateGuide(@RequestBody(required = false) String body) {
        return doForward("/api/ai/generate-guide", HttpMethod.POST, body);
    }

    @PostMapping(value = "/api/ai/generate-spec", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardGenerateSpec(@RequestBody(required = false) String body) {
        return doForward("/api/ai/generate-spec", HttpMethod.POST, body);
    }

    @PostMapping(value = "/api/ai/generate-requirement", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardGenerateRequirement(@RequestBody(required = false) String body) {
        return doForward("/api/ai/generate-requirement", HttpMethod.POST, body);
    }

    @PostMapping(value = "/api/ai/generate-delivery", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardGenerateDelivery(@RequestBody(required = false) String body) {
        return doForward("/api/ai/generate-delivery", HttpMethod.POST, body);
    }

    @PostMapping(value = "/api/ai/generate-testcases", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardGenerateTestcases(@RequestBody(required = false) String body) {
        return doForward("/api/ai/generate-testcases", HttpMethod.POST, body);
    }

    // ========== AiRagController 对应端点 ==========

    @PostMapping(value = "/api/ai/rag/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardRagSync(
            @RequestParam(required = false) String docType,
            @RequestBody(required = false) String body) {
        return doForward(buildUrl("/api/ai/rag/sync", docType), HttpMethod.POST, body, true);
    }

    @GetMapping(value = "/api/ai/rag/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardRagHealth() {
        return doForward("/api/ai/rag/health", HttpMethod.GET, null);
    }

    @GetMapping(value = "/api/ai/rag/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardRagConfig() {
        return doForward("/api/ai/rag/config", HttpMethod.GET, null);
    }

    @PostMapping(value = "/api/ai/rag/index", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardRagIndex(
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String collectionName,
            @RequestBody(required = false) String body) {
        String target;
        if (collectionName != null && !collectionName.isBlank()) {
            target = "/api/ai/rag/index?collectionName=" + urlEncodeQuery(collectionName);
        } else if (docType != null && !docType.isBlank()) {
            target = "/api/ai/rag/index?docType=" + urlEncodeQuery(docType);
        } else {
            target = "/api/ai/rag/index";
        }
        return doForward(target, HttpMethod.POST, body, true);
    }

    @PostMapping(value = "/api/ai/rag/load", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardRagLoad(
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String collectionName,
            @RequestBody(required = false) String body) {
        String target = buildUrlWithCollection("/api/ai/rag/load", docType, collectionName);
        return doForward(target, HttpMethod.POST, body, true);
    }

    @PostMapping(value = "/api/ai/rag/release", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forwardRagRelease(
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String collectionName,
            @RequestBody(required = false) String body) {
        String target = buildUrlWithCollection("/api/ai/rag/release", docType, collectionName);
        return doForward(target, HttpMethod.POST, body, true);
    }

    // ========== 内部工具 ==========

    private ResponseEntity<String> doForward(String path, HttpMethod method, String body) {
        return doForward(path, method, body, false);
    }

    private ResponseEntity<String> doForward(String path, HttpMethod method, String body, boolean pathHasQuery) {
        String serviceUrl = properties.getServiceUrl();
        if (serviceUrl == null || serviceUrl.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":\"error\",\"message\":\"client mode requires service-url\"}");
        }

        String targetUrl = buildTargetUrl(serviceUrl, path);

        long start = System.currentTimeMillis();
        log.info("[ai-client] {} -> {}", method, targetUrl);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(body == null ? "" : body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    targetUrl,
                    method,
                    entity,
                    String.class);

            long cost = System.currentTimeMillis() - start;
            log.info("[ai-client] {} {} -> {} ({} ms)", method, targetUrl,
                    response.getStatusCode().value(), cost);

            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getBody());
        } catch (HttpStatusCodeException ex) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[ai-client] {} {} -> {} ({} ms): {}",
                    method, targetUrl, ex.getStatusCode().value(), cost, ex.getMessage());
            return ResponseEntity.status(ex.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ex.getResponseBodyAsString());
        } catch (ResourceAccessException ex) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[ai-client] {} {} -> 远程不可达 ({} ms): {}",
                    method, targetUrl, cost, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":\"error\",\"message\":\"remote service unreachable: "
                            + escapeJson(serviceUrl) + "\"}");
        } catch (Exception ex) {
            long cost = System.currentTimeMillis() - start;
            log.error("[ai-client] {} {} -> 转发失败 ({} ms)", method, targetUrl, cost, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":\"error\",\"message\":\"forward failed: "
                            + escapeJson(ex.getMessage()) + "\"}");
        }
    }

    private String buildUrl(String base, String docType) {
        if (docType == null || docType.isBlank()) return base;
        return base + "?docType=" + urlEncodeQuery(docType);
    }

    private String buildUrlWithCollection(String base, String docType, String collectionName) {
        if (collectionName != null && !collectionName.isBlank()) {
            return base + "?collectionName=" + urlEncodeQuery(collectionName);
        }
        if (docType != null && !docType.isBlank()) {
            return base + "?docType=" + urlEncodeQuery(docType);
        }
        return base;
    }

    private String buildTargetUrl(String serviceUrl, String path) {
        String url = serviceUrl.endsWith("/") ? serviceUrl.substring(0, serviceUrl.length() - 1) : serviceUrl;
        return url + path;
    }

    private String urlEncodeQuery(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
