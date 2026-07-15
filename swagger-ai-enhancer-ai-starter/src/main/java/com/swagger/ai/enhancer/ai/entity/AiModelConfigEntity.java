package com.swagger.ai.enhancer.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 模型配置实体。
 *
 * 表：ai_model_config（一行；is_enabled=1 表示当前生效）。
 * 字段覆盖：model_type / provider / api_key / base_url / model_name /
 *           temperature / max_tokens / timeout_seconds /
 *           top_p / frequency_penalty / presence_penalty /
 *           is_enabled / created_at / updated_at。
 */
@Data
@TableName("ai_model_config")
public class AiModelConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 模型类型（llm / embedding / asr / tts 等）。默认 llm。
     */
    @TableField("model_type")
    private String modelType = "llm";

    /**
     * 当前使用的 LLM 提供者：ollama / openai-compatible / aliyun-bailian。
     */
    @TableField("provider")
    private String provider = "ollama";

    /**
     * API Key（敏感字段；返回给前端时脱敏为"***"）。
     */
    @TableField("api_key")
    private String apiKey = "";

    /**
     * API 基础地址（如 http://localhost:11434 / https://api.openai.com/v1）。
     */
    @TableField("base_url")
    private String baseUrl = "http://localhost:11434";

    /**
     * 模型名称（如 llama3:latest / gpt-4o / qwen-plus）。
     */
    @TableField("model_name")
    private String modelName = "llama3:latest";

    /**
     * 生成温度（0.0 ~ 1.0）。
     */
    @TableField("temperature")
    private Double temperature = 0.3;

    /**
     * 最大生成 Token 数。
     */
    @TableField("max_tokens")
    private Integer maxTokens = 4096;

    /**
     * 请求超时（秒）。
     */
    @TableField("timeout_seconds")
    private Integer timeoutSeconds = 120;

    /**
     * Top-P 采样（0.0 ~ 1.0）。
     */
    @TableField("top_p")
    private Double topP = 1.0;

    /**
     * Frequency Penalty（-2.0 ~ 2.0）。
     */
    @TableField("frequency_penalty")
    private Double frequencyPenalty = 0.0;

    /**
     * Presence Penalty（-2.0 ~ 2.0）。
     */
    @TableField("presence_penalty")
    private Double presencePenalty = 0.0;

    /**
     * Embedding 模型提供者：ollama / openai / aliyun-bailian。默认 ollama。
     */
    @TableField("embedding_provider")
    private String embeddingProvider = "ollama";

    /**
     * Embedding 模型名称（如 nomic-embed-text:latest / text-embedding-3-small）。
     */
    @TableField("embedding_model")
    private String embeddingModel = "nomic-embed-text:latest";

    /**
     * 探测得到的 Embedding 向量维度，-1 表示未探测。
     * 由 AiModelConfigService 在 saveConfig 时通过 HTTP 探测模型实际维度后写入；
     * 启动时由 loadFromDb 读取恢复到 properties.rag.dimension。
     */
    @TableField("embedding_dimension")
    private Integer embeddingDimension = -1;

    @TableField("max_context_tokens")
    private Integer maxContextTokens;

    @TableField("max_output_tokens")
    private Integer maxOutputTokens;

    @TableField("model_family")
    private String modelFamily;

    @TableField("quantization")
    private String quantization;

    @TableField("model_size_gb")
    private BigDecimal modelSizeGb;

    @TableField("prompt_price_per_1k_tokens")
    private BigDecimal promptPricePer1kTokens;

    @TableField("completion_price_per_1k_tokens")
    private BigDecimal completionPricePer1kTokens;

    @TableField("knowledge_cutoff_date")
    private LocalDate knowledgeCutoffDate;

    @TableField("capabilities")
    private String capabilities;

    /**
     * 启用状态（1=启用，0=禁用）。主键冲突时按 is_enabled=1 筛选。
     */
    @TableField("is_enabled")
    private Integer isEnabled = 1;

    @TableField("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @TableField("updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
}
