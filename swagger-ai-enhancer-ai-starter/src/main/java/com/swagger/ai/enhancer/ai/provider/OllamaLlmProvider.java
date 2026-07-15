package com.swagger.ai.enhancer.ai.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * Ollama LLM 提供者实现。调用本地 Ollama 的 /api/chat 端点。
 */
@Slf4j
public class OllamaLlmProvider implements LlmProvider {

    private static final String CHAT_ENDPOINT = "/api/chat";

    private final AiEnhancerProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OllamaLlmProvider(AiEnhancerProperties properties) {
        this.properties = properties;
        this.restTemplate = buildRestTemplate(properties.getLlm().getTimeoutSeconds());
        this.objectMapper = new ObjectMapper();
    }

    private static RestTemplate buildRestTemplate(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int ms = Math.max(1, timeoutSeconds) * 1000;
        factory.setConnectTimeout(ms);
        factory.setReadTimeout(ms);
        return new RestTemplate(factory);
    }

    @Override
    public String generate(String prompt) {
        return generate(null, prompt);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        AiEnhancerProperties.OllamaConfig config = properties.getLlm().getOllama();
        AiEnhancerProperties.LlmConfig llm = properties.getLlm();

        OllamaChatRequest request = OllamaChatRequest.builder()
                .model(config.getModel())
                .stream(false)
                .messages(buildMessages(systemPrompt, userPrompt))
                .options(OllamaOptions.builder()
                        .temperature(llm.getTemperature())
                        .numPredict(llm.getMaxTokens())
                        .build())
                .build();

        String url = config.getBaseUrl() + CHAT_ENDPOINT;
        long start = System.currentTimeMillis();
        log.info("调用 Ollama: url={}, model={}", url, config.getModel());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                headers.setBearerAuth(config.getApiKey());
            }
            HttpEntity<OllamaChatRequest> entity = new HttpEntity<>(request, headers);

            String raw = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("message").path("content");
            String result = content.isTextual() ? content.asText() : "";

            long cost = System.currentTimeMillis() - start;
            log.info("Ollama 调用完成，耗时 {}ms", cost);

            if (result.isBlank()) {
                throw new RuntimeException("Ollama 返回内容为空: " + raw);
            }
            return result.trim();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("Ollama 调用失败，耗时 {}ms: {}", cost, e.getMessage());
            throw new RuntimeException("Ollama 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }

    @Override
    public int getMaxConcurrency() {
        return 2;
    }

    private static List<OllamaMessage> buildMessages(String systemPrompt, String userPrompt) {
        List<OllamaMessage> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new OllamaMessage("system", systemPrompt));
        }
        messages.add(new OllamaMessage("user", userPrompt == null ? "" : userPrompt));
        return messages;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OllamaChatRequest {
        private String model;
        private boolean stream;
        private List<OllamaMessage> messages;
        private OllamaOptions options;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OllamaMessage {
        private String role;
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OllamaOptions {
        private double temperature;
        private int numPredict;
    }
}
