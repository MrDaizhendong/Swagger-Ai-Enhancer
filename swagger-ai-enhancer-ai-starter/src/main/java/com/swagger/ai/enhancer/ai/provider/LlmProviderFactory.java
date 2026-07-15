package com.swagger.ai.enhancer.ai.provider;

import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;

/**
 * LLM 提供者工厂：根据配置中的 provider 名称返回对应实现。
 */
public class LlmProviderFactory {

    private final AiEnhancerProperties properties;

    public LlmProviderFactory(AiEnhancerProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据 llm.provider 获取当前 LLM 提供者。
     */
    public LlmProvider getProvider() {
        String provider = properties.getLlm().getProvider();
        if (provider == null) {
            throw new IllegalArgumentException("llm.provider 未配置");
        }
        switch (provider.toLowerCase()) {
            case "ollama":
                return new OllamaLlmProvider(properties);
            case "openai":
            case "deepseek":
            case "openai-compatible":
            case "kimi":
            case "moonshot":
            case "chatglm":
                return new OpenAiCompatibleLlmProvider(properties);
            case "aliyun-bailian":
            case "bailian":
            case "dashscope":
                return new AliyunBailianLlmProvider(properties);
            default:
                throw new IllegalArgumentException("未知的 LLM 提供者: " + provider
                        + "（已支持：ollama, openai-compatible, aliyun-bailian）");
        }
    }
}
