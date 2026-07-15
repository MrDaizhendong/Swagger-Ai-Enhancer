package com.swagger.ai.enhancer.ai.provider;

/**
 * LLM 提供者接口。所有具体 LLM 提供者实现本接口，供调用方通过工厂获取。
 */
public interface LlmProvider {

    /**
     * 调用 LLM 生成文本。
     *
     * @param prompt 用户提示词
     * @return 生成的文本
     */
    String generate(String prompt);

    /**
     * 调用 LLM 生成文本（支持系统提示词 + 用户提示词）。
     *
     * @param systemPrompt 系统提示词（角色/任务定义）
     * @param userPrompt   用户提示词（具体输入）
     * @return 生成的文本
     */
    String generate(String systemPrompt, String userPrompt);

    /**
     * 获取提供者名称（如 "ollama", "deepseek"）。
     *
     * @return 提供者名称
     */
    String getProviderName();

    /**
     * 获取最大并发调用数。云端模型默认高并发，本地模型应返回较小值。
     *
     * @return 最大并发数
     */
    default int getMaxConcurrency() {
        return 10;
    }
}
