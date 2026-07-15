package com.swagger.ai.enhancer.ai.controller;

import com.swagger.ai.enhancer.ai.entity.AiModelConfigEntity;
import com.swagger.ai.enhancer.ai.service.AiModelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 模型配置 REST 控制器。
 *
 * 端点：
 *   GET  /api/ai/model-config   返回当前启用的配置（apiKey 脱敏为 "***"；无配置则返回 {}）
 *   PUT  /api/ai/model-config   保存配置（upsert）；保存后立即生效
 *   POST /api/ai/model-config/test-connection  根据 provider 测试连通性（5 秒超时）
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/ai/model-config", produces = MediaType.APPLICATION_JSON_VALUE)
public class AiModelConfigController {

    private final AiModelConfigService service;

    @Autowired
    public AiModelConfigController(AiModelConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> getModelConfig() {
        try {
            AiModelConfigEntity entity = service.getConfigForResponse();
            if (entity == null) {
                // 无 DB 配置：返回空 Map（前端据此判断可立即写入）
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("status", "ok");
                r.put("config", new LinkedHashMap<String, Object>());
                return ResponseEntity.ok(r);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "ok");
            r.put("config", entity);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            log.warn("[ai-model-config] 读取配置失败: {}", e.getMessage());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "error");
            r.put("message", e.getMessage());
            return ResponseEntity.status(500).body(r);
        }
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> saveModelConfig(
            @RequestBody(required = false) AiModelConfigEntity entity) {
        if (entity == null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "error");
            r.put("message", "请求体不能为空");
            return ResponseEntity.badRequest().body(r);
        }
        try {
            service.saveConfig(entity);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "ok");
            r.put("message", "AI 模型配置已保存");
            log.info("[ai-model-config] 已保存：provider={}, model={}",
                    entity.getProvider(), entity.getModelName());
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            log.error("[ai-model-config] 保存失败：{}", e.getMessage(), e);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "error");
            r.put("message", e.getMessage());
            return ResponseEntity.status(500).body(r);
        }
    }

    /**
     * 测试 AI 模型服务连通性。
     * <p>请求体示例：{ "provider": "ollama", "baseUrl": "http://localhost:11434",
     * "apiKey": "sk-...", "modelName": "llama3:latest" }</p>
     * <p>provider 支持：ollama / llama.cpp / vllm / openai / deepseek / glm / kimi / aliyun-bailian</p>
     */
    @PostMapping(value = "/test-connection", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> testConnection(
            @RequestBody(required = false) Map<String, Object> body) {
        long start = System.nanoTime();
        Map<String, Object> payload = body != null ? body : new LinkedHashMap<>();
        String provider = strVal(payload.get("provider"));
        String baseUrl = strVal(payload.get("baseUrl"));
        String apiKey = strVal(payload.get("apiKey"));
        String modelName = strVal(payload.get("modelName"));

        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            if (provider == null || provider.isBlank()) {
                resp.put("status", "error");
                resp.put("message", "缺少必填参数：provider");
                return ResponseEntity.badRequest().body(resp);
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                resp.put("status", "error");
                resp.put("message", "缺少必填参数：baseUrl");
                return ResponseEntity.badRequest().body(resp);
            }

            String normalized = provider.trim().toLowerCase();
            switch (normalized) {
                case "ollama":
                    testOllama(baseUrl);
                    break;
                case "llama.cpp":
                case "llamacpp":
                case "llama-cpp":
                    testLlamaCpp(baseUrl);
                    break;
                case "vllm":
                    testVllm(baseUrl);
                    break;
                case "openai":
                case "deepseek":
                case "glm":
                case "kimi":
                    testOpenAiCompatible(normalized, baseUrl, apiKey);
                    break;
                case "aliyun-bailian":
                    testAliyunBailian(baseUrl, apiKey);
                    break;
                default:
                    resp.put("status", "error");
                    resp.put("message", "不支持的模型提供者：" + provider
                            + "（可选：ollama / llama.cpp / vllm / openai / deepseek / glm / kimi / aliyun-bailian）");
                    return ResponseEntity.badRequest().body(resp);
            }

            resp.put("status", "ok");
            resp.put("provider", provider);
            resp.put("message", "连接成功");
            long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            log.info("[ai-model-config] 测试连接成功：provider={}, baseUrl={}, 耗时={}ms",
                    provider, baseUrl, costMs);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            log.warn("[ai-model-config] 测试连接失败：provider={}, baseUrl={}：{}（耗时 {}ms）",
                    provider, baseUrl, e.getMessage(), costMs);
            resp.put("status", "error");
            resp.put("provider", provider);
            resp.put("message", "连接失败：" + e.getMessage());
            return ResponseEntity.status(502).body(resp);
        }
    }

    // ============ provider 具体测试实现 ============

    private void testOllama(String baseUrl) throws Exception {
        // Ollama 官方：GET /api/tags 列出本地模型
        String url = normalizeBase(baseUrl) + "/api/tags";
        String text = httpGet(url, null, 5000);
        if (text == null || (!text.contains("models") && !text.contains("name"))) {
            throw new RuntimeException("Ollama 返回内容不符合预期（未找到 models / name）");
        }
    }

    private void testLlamaCpp(String baseUrl) throws Exception {
        // llama.cpp 提供标准 HTTP Server：GET /health 返回 { status: "ok" }
        String url = normalizeBase(baseUrl) + "/health";
        httpGet(url, null, 5000);
    }

    private void testVllm(String baseUrl) throws Exception {
        // vLLM OpenAI 兼容 API：GET /health 或 /v1/models
        String url = normalizeBase(baseUrl) + "/health";
        try {
            httpGet(url, null, 5000);
            return;
        } catch (Exception ignored) {
            // 某些旧版本 vLLM 可能仅暴露 OpenAI 兼容端点
        }
        httpGet(normalizeBase(baseUrl) + "/v1/models", null, 5000);
    }

    private void testOpenAiCompatible(String providerName, String baseUrl, String apiKey) throws Exception {
        // 统一：GET <baseUrl>/models，Header Authorization: Bearer <apiKey>
        // 默认 baseUrl 如未指定 /v1 后缀，自动补齐 /v1
        String base = normalizeBase(baseUrl);
        if (!base.endsWith("/v1") && !base.endsWith("/v1/")) {
            // 保留用户原 baseUrl 直接拼接 /models 测试一次；失败后再尝试 {base}/v1/models
            String url = base + "/models";
            try {
                httpGet(url, apiKey, 5000);
                return;
            } catch (Exception ignored) {
                // 回退：{base}/v1/models
            }
            httpGet(normalizeBase(base) + "/v1/models", apiKey, 5000);
            return;
        }
        httpGet(base + "/models", apiKey, 5000);
    }

    private void testAliyunBailian(String baseUrl, String apiKey) throws Exception {
        // 阿里百炼 DashScope 兼容 OpenAI 风格，默认 baseUrl:
        //   https://dashscope.aliyuncs.com/compatible-mode/v1
        // 健康检查使用 GET /models；若调用失败，降级为 GET / （返回 200 即可）
        String base = normalizeBase(baseUrl);
        String url = base + "/models";
        try {
            httpGet(url, apiKey, 5000);
            return;
        } catch (Exception ignored) {
            // 某些版本百炼网关可能不直接暴露 /models，退化为检查可达性
        }
        httpGet(base, apiKey, 5000);
    }

    // ============ HTTP 工具 ============

    /** GET 方法；若 apiKey 非空则附加 Authorization: Bearer <apiKey>。返回响应体文本。 */
    private String httpGet(String url, String apiKey, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code < 200 || code >= 400) {
            String errText = readText(conn);
            String msg = "HTTP " + code;
            if (errText != null && !errText.isBlank()) msg += " - " + errText.trim();
            throw new RuntimeException(msg);
        }
        return readText(conn);
    }

    private String readText(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /** 去除末尾斜杠，保证后续拼接一致 */
    private String normalizeBase(String baseUrl) {
        if (baseUrl == null) return "";
        String b = baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String strVal(Object o) {
        return o == null ? null : o.toString();
    }
}
