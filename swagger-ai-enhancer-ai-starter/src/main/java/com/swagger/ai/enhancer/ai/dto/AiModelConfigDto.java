package com.swagger.ai.enhancer.ai.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class AiModelConfigDto {

    private Integer maxContextTokens;

    private Integer maxOutputTokens;

    private String modelFamily;

    private String quantization;

    private BigDecimal modelSizeGb;

    private BigDecimal promptPricePer1kTokens;

    private BigDecimal completionPricePer1kTokens;

    private LocalDate knowledgeCutoffDate;

    private String capabilities;
}
