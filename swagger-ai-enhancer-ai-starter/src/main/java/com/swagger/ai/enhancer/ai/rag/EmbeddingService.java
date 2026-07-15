package com.swagger.ai.enhancer.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 服务：调用本地 Ollama 的 /api/embeddings 端点，将文本转为向量。
 */
@Slf4j
public class EmbeddingService {

    private static final String EMBED_ENDPOINT = "/api/embeddings";

    private final AiEnhancerProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmbeddingService(AiEnhancerProperties properties) {
        this.properties = properties;
        this.restTemplate = buildRestTemplate(Math.max(60, properties.getLlm().getTimeoutSeconds()));
    }

    private static RestTemplate buildRestTemplate(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        return new RestTemplate(factory);
    }

    /**
     * 对单段文本计算向量。
     *
     * @param text 文本内容
     * @return 向量（List<Double>）
     */
    public List<Double> embed(String text) {
        String url = properties.getRag().getEmbeddingUrl() + EMBED_ENDPOINT;
        OllamaEmbedRequest body = new OllamaEmbedRequest(
                properties.getRag().getEmbeddingModel(),
                text == null ? "" : text
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<OllamaEmbedRequest> entity = new HttpEntity<>(body, headers);
            String raw = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode embeddingNode = root.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new RuntimeException("Ollama embedding 响应缺少 embedding 字段：" + raw);
            }
            List<Double> vector = new ArrayList<>(embeddingNode.size());
            for (JsonNode v : embeddingNode) {
                vector.add(v.asDouble());
            }
            return vector;
        } catch (Exception e) {
            log.error("Embedding 调用失败（{}）：{}", url, e.getMessage());
            throw new RuntimeException("Embedding 调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 批量计算多段文本的向量。内部逐段调用（Ollama 当前仅支持单 prompt）。
     *
     * @param texts 文本列表
     * @return 与输入顺序一致的向量列表
     */
    public List<List<Double>> embedBatch(List<String> texts) {
        List<List<Double>> vectors = new ArrayList<>(texts.size());
        int i = 0;
        for (String text : texts) {
            vectors.add(embed(text));
            i++;
            if (i % 10 == 0) {
                log.info("Embedding 进度：{}/{}", i, texts.size());
            }
        }
        return vectors;
    }

    @Data
    @AllArgsConstructor
    public static class OllamaEmbedRequest {
        private String model;
        private String prompt;
    }
}
