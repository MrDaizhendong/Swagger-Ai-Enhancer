package com.swagger.ai.enhancer.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云百炼（DashScope）大模型 API 提供者。
 * 端点：https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation
 * 或直接使用兼容 OpenAI 的 /v1/chat/completions 接口。
 *
 * 本实现通过可配置 baseUrl 与模型名，默认使用 OpenAI 兼容模式（DashScope 1.8+ 已支持）。
 */
@Slf4j
public class AliyunBailianLlmProvider implements LlmProvider {

    private final AiEnhancerProperties.AliyunBailianConfig config;
    private final int timeoutSeconds;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AliyunBailianLlmProvider(AiEnhancerProperties properties) {
        this.config = properties.getLlm().getAliyunBailian();
        this.timeoutSeconds = Math.max(10, properties.getLlm().getTimeoutSeconds());
        this.restTemplate = buildRestTemplate(timeoutSeconds);
    }

    private static RestTemplate buildRestTemplate(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        return new RestTemplate(factory);
    }

    @Override
    public String generate(String prompt) {
        return generate(null, prompt);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        String baseUrl = stripTrailingSlash(config.getBaseUrl());
        String url = baseUrl + "/chat/completions";

        ChatRequest body = ChatRequest.builder()
                .model(config.getModel())
                .messages(buildMessages(systemPrompt, userPrompt))
                .build();

        log.info("调用 阿里云百炼：{}，模型 {}", url, config.getModel());
        long start = System.currentTimeMillis();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                headers.set("Authorization", "Bearer " + config.getApiKey());
            }
            HttpEntity<ChatRequest> entity = new HttpEntity<>(body, headers);
            String raw = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) {
                throw new RuntimeException("响应缺少 choices 字段：" + raw);
            }
            JsonNode content = choices.get(0).path("message").path("content");
            String result = content.isTextual() ? content.asText() : "";
            if (result.isBlank()) {
                throw new RuntimeException("返回内容为空：" + raw);
            }
            log.info("阿里云百炼 调用完成，耗时 {}ms", System.currentTimeMillis() - start);
            return result.trim();
        } catch (Exception e) {
            log.error("阿里云百炼 调用失败：{}", e.getMessage());
            throw new RuntimeException("阿里云百炼 调用失败：" + e.getMessage(), e);
        }
    }

    @Override
    public String getProviderName() {
        return "aliyun-bailian";
    }

    @Override
    public int getMaxConcurrency() {
        return 10;
    }

    private static List<ChatMessage> buildMessages(String systemPrompt, String userPrompt) {
        List<ChatMessage> msgs = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.add(new ChatMessage("system", systemPrompt));
        }
        msgs.add(new ChatMessage("user", userPrompt == null ? "" : userPrompt));
        return msgs;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        String u = url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRequest {
        private String model;
        private List<ChatMessage> messages;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;
        private String content;
    }
}
