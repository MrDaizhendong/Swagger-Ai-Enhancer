package com.swagger.ai.enhancer.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.entity.AiModelConfigEntity;
import com.swagger.ai.enhancer.ai.mapper.AiModelConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;

/**
 * AI 模型配置服务（ai_model_config）。
 *
 * 职责：
 *   - getConfig()              读取 is_enabled=1 的配置（单例模式；若无返回 null）
 *   - saveConfig(entity)       upsert：id>0 则 update，否则 insert；保存后立即应用到 properties
 *   - getConfigForResponse()   返回给前端；apiKey 脱敏为 "***"
 *   - applyConfigToProperties()  将 DB 配置覆盖到 AiEnhancerProperties.llm
 *   - @PostConstruct 启动加载：DB 为空/失败时回退 YAML 默认值
 */
@Slf4j
public class AiModelConfigService {

    private final AiModelConfigMapper mapper;
    private final AiEnhancerProperties properties;

    @Autowired
    public AiModelConfigService(AiModelConfigMapper mapper, AiEnhancerProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 启动时：从数据库加载配置覆盖 YAML 默认值；DB 为空/失败时保持 YAML 配置 */
    @PostConstruct
    public void loadFromDb() {
        AiModelConfigEntity entity;
        try {
            entity = getConfig();
        } catch (Exception e) {
            log.warn("[ai-model-config] 启动阶段读取数据库失败（可能尚未初始化），继续使用 YAML 默认值：{}",
                    e.getMessage());
            return;
        }
        if (entity == null) {
            log.info("[ai-model-config] 数据库为空，继续使用 YAML 默认配置（provider={}, model={}）",
                    properties.getLlm().getProvider(), properties.getLlm().getOllama().getModel());
        } else {
            applyConfigToProperties(entity);
            log.info("[ai-model-config] 已从数据库加载：provider={}, baseUrl={}, model={}",
                    entity.getProvider(), entity.getBaseUrl(), entity.getModelName());
        }
        // ===== 检查向量维度是否已配置，未配置时打印 warn，不阻断启动 =====
        if (properties.getRag().getDimension() <= 0) {
            log.warn("[ai-model-config] Embedding 模型维度未配置，部分功能可能不可用。请前往 Swagger UI 的「AI 模型设置」面板，配置 Embedding 提供者和模型名称后点击保存，系统将自动探测模型维度。");
        } else {
            log.info("[ai-model-config] 当前 Embedding 向量维度：{}", properties.getRag().getDimension());
        }
    }

    /** 读取 is_enabled=1 的配置（单例；多写保留第一条）；若无返回 null */
    public AiModelConfigEntity getConfig() {
        QueryWrapper<AiModelConfigEntity> qw = new QueryWrapper<>();
        qw.eq("is_enabled", 1).orderByDesc("id").last("LIMIT 1");
        AiModelConfigEntity entity = mapper.selectOne(qw);
        return entity;
    }

    /**
     * 保存配置：
     *   - 以 provider 作为唯一键判断记录是否存在（LambdaQueryWrapper.eq(provider)）
     *   - 存在：复用其 id 执行 updateById
     *   - 不存在：insert 新记录（insert 前先置 is_enabled=1 的旧记录为 0，保持单例）
     * 保存后立即应用到 AiEnhancerProperties，并在 embedding 配置变更时自动探测模型维度。
     *
     * @param entity 前端传入的配置（apiKey 若为 "***" 表示不修改密码，保持原值）
     */
    public void saveConfig(AiModelConfigEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        // 读取数据库中现有配置（用于密码回退 + 维度对比）
        AiModelConfigEntity existing = null;
        try {
            existing = getConfig();
        } catch (Exception ignored) {
            // ignore
        }
        // apiKey == "***" 保持 DB 原值（防止前端误传空值）
        if ("***".equals(entity.getApiKey()) && existing != null) {
            entity.setApiKey(existing.getApiKey());
        }
        if (entity.getUpdatedAt() == null) {
            entity.setUpdatedAt(java.time.LocalDateTime.now());
        }

        // ===== Embedding 维度探测：每次保存都执行（**前置**到 UPDATE/INSERT 之前），
        //       确保 entity.setEmbeddingDimension(dim) 能被持久化到数据库 =====
        String newEmbProvider = entity.getEmbeddingProvider();
        String newEmbModel = entity.getEmbeddingModel();
        String embProviderForProbe = (newEmbProvider == null || newEmbProvider.isBlank()) ? "ollama" : newEmbProvider.trim();
        String embModelForProbe = (newEmbModel == null || newEmbModel.isBlank()) ? "nomic-embed-text:latest" : newEmbModel.trim();
        String embBaseUrl = entity.getBaseUrl(); // 复用 LLM baseUrl
        String embApiKey = entity.getApiKey();  // 复用 LLM API Key（按需）
        Integer dim = probeEmbeddingDimension(embProviderForProbe, embModelForProbe, embBaseUrl, embApiKey);
        if (dim != null && dim > 0) {
            // 1) 写入 entity，让下面的 UPDATE/INSERT 能把 dim 写回 ai_model_config.embedding_dimension
            entity.setEmbeddingDimension(dim);
            // 2) 同时写入内存 properties.rag.dimension，供同步流程 createCollection 使用
            AiEnhancerProperties.RagConfig rag = properties.getRag();
            if (rag != null) {
                rag.setDimension(dim);
            }
            log.info("[ai-model-config] Embedding 维度探测完成（provider={}, model={}），dimension={}",
                    embProviderForProbe, embModelForProbe, dim);
        } else {
            log.warn("[ai-model-config] Embedding 维度探测未返回值（provider={}, model={}），保持原有 dimension 不变",
                    embProviderForProbe, embModelForProbe);
        }

        // ===== 基于 provider 判断记录是否存在（替代 entity.getId() 判断，防止 Duplicate entry） =====
        LambdaQueryWrapper<AiModelConfigEntity> lqw = new LambdaQueryWrapper<>();
        lqw.eq(AiModelConfigEntity::getProvider, entity.getProvider());
        AiModelConfigEntity existingByProvider = null;
        try {
            existingByProvider = mapper.selectOne(lqw);
        } catch (Exception ignored) {
            // ignore（若 DB 尚未初始化等）
        }
        if (existingByProvider != null) {
            // 已存在：先逐字段对比新旧数据，无变化则跳过 UPDATE
            boolean dataChanged = isDataChanged(entity, existingByProvider);
            if (!dataChanged) {
                log.info("[ai-model-config] AI 模型配置无变化，跳过更新（provider={}）", entity.getProvider());
                return;
            }
            // 复用其 id，按主键 update
            entity.setId(existingByProvider.getId());
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(existingByProvider.getCreatedAt());
            }
            if (entity.getIsEnabled() == null) {
                entity.setIsEnabled(1);
            }
            mapper.updateById(entity);
            log.info("[ai-model-config] 已更新（id={}, provider={}）", entity.getId(), entity.getProvider());
        } else {
            // 不存在：insert（保持单例：先把 is_enabled=1 的旧记录置为 0）
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(java.time.LocalDateTime.now());
            }
            if (entity.getIsEnabled() == null) {
                entity.setIsEnabled(1);
            }
            try {
                AiModelConfigEntity patch = new AiModelConfigEntity();
                patch.setIsEnabled(0);
                QueryWrapper<AiModelConfigEntity> qw = new QueryWrapper<>();
                qw.eq("is_enabled", 1);
                int rows = mapper.update(patch, qw);
                if (rows > 0) {
                    log.info("[ai-model-config] 批量禁用旧配置：影响 {} 行", rows);
                }
                if (rows > 1) {
                    log.warn("[ai-model-config] 批量禁用旧配置影响 {} 行，可能存在并发写入问题，请检查数据一致性", rows);
                }
            } catch (Exception ignored) {
                // ignore
            }
            mapper.insert(entity);
            log.info("[ai-model-config] 已插入（id={}, provider={}）", entity.getId(), entity.getProvider());
        }
        applyConfigToProperties(entity);

        try {
            probeModelCapabilities(entity);
        } catch (Exception e) {
            log.warn("[ai-model-config] 模型能力探测失败，不影响配置保存：{}", e.getMessage());
        }
    }

    /**
     * 逐字段对比新旧 AI 模型配置数据，用于判断是否需要执行 UPDATE。
     * 排除 id / createdAt / updatedAt。apiKey 特殊处理：若新值为 "***" 视为未变更。
     *
     * @param newEntity 前端传入的新配置（已应用 apiKey 回退）
     * @param oldEntity 数据库中已存在的配置
     * @return true 表示有字段差异需要 UPDATE；false 表示无变化
     */
    private boolean isDataChanged(AiModelConfigEntity newEntity, AiModelConfigEntity oldEntity) {
        if (oldEntity == null) return true;
        if (newEntity == null) return false;

        if (!java.util.Objects.equals(normalizeStr(newEntity.getModelType()), normalizeStr(oldEntity.getModelType())))
            return true;
        if (!java.util.Objects.equals(normalizeStr(newEntity.getProvider()), normalizeStr(oldEntity.getProvider())))
            return true;
        // apiKey 特殊处理：若新值为 "***" 视为未修改（前端传入的脱敏标记）
        if (!"***".equals(newEntity.getApiKey())
                && !java.util.Objects.equals(normalizeStr(newEntity.getApiKey()), normalizeStr(oldEntity.getApiKey())))
            return true;
        if (!java.util.Objects.equals(normalizeStr(newEntity.getBaseUrl()), normalizeStr(oldEntity.getBaseUrl())))
            return true;
        if (!java.util.Objects.equals(normalizeStr(newEntity.getModelName()), normalizeStr(oldEntity.getModelName())))
            return true;

        if (!java.util.Objects.equals(newEntity.getTemperature(), oldEntity.getTemperature())) return true;
        if (!java.util.Objects.equals(newEntity.getMaxTokens(), oldEntity.getMaxTokens())) return true;
        if (!java.util.Objects.equals(newEntity.getTimeoutSeconds(), oldEntity.getTimeoutSeconds())) return true;
        if (!java.util.Objects.equals(newEntity.getTopP(), oldEntity.getTopP())) return true;
        if (!java.util.Objects.equals(newEntity.getFrequencyPenalty(), oldEntity.getFrequencyPenalty())) return true;
        if (!java.util.Objects.equals(newEntity.getPresencePenalty(), oldEntity.getPresencePenalty())) return true;
        if (!java.util.Objects.equals(newEntity.getIsEnabled(), oldEntity.getIsEnabled())) return true;

        // Embedding 字段
        if (!java.util.Objects.equals(normalizeStr(newEntity.getEmbeddingProvider()),
                normalizeStr(oldEntity.getEmbeddingProvider())))
            return true;
        if (!java.util.Objects.equals(normalizeStr(newEntity.getEmbeddingModel()),
                normalizeStr(oldEntity.getEmbeddingModel())))
            return true;

        // 模型能力字段
        if (!java.util.Objects.equals(newEntity.getMaxContextTokens(), oldEntity.getMaxContextTokens())) return true;
        if (!java.util.Objects.equals(newEntity.getMaxOutputTokens(), oldEntity.getMaxOutputTokens())) return true;
        if (!java.util.Objects.equals(normalizeStr(newEntity.getModelFamily()), normalizeStr(oldEntity.getModelFamily()))) return true;
        if (!java.util.Objects.equals(normalizeStr(newEntity.getQuantization()), normalizeStr(oldEntity.getQuantization()))) return true;
        if (!java.util.Objects.equals(newEntity.getModelSizeGb(), oldEntity.getModelSizeGb())) return true;
        if (!java.util.Objects.equals(newEntity.getPromptPricePer1kTokens(), oldEntity.getPromptPricePer1kTokens())) return true;
        if (!java.util.Objects.equals(newEntity.getCompletionPricePer1kTokens(), oldEntity.getCompletionPricePer1kTokens())) return true;
        if (!java.util.Objects.equals(newEntity.getKnowledgeCutoffDate(), oldEntity.getKnowledgeCutoffDate())) return true;
        if (!java.util.Objects.equals(normalizeStr(newEntity.getCapabilities()), normalizeStr(oldEntity.getCapabilities()))) return true;

        return false;
    }

    /** null / 空白 统一为 "" */
    private static String normalizeStr(String s) {
        return s == null ? "" : s.trim();
    }

    /** 返回给前端时 apiKey 脱敏 */
    public AiModelConfigEntity getConfigForResponse() {
        AiModelConfigEntity entity = getConfig();
        if (entity == null) return null;
        // 返回浅拷贝，避免修改影响 DB 内容
        AiModelConfigEntity copy = new AiModelConfigEntity();
        copy.setId(entity.getId());
        copy.setModelType(entity.getModelType());
        copy.setProvider(entity.getProvider());
        copy.setApiKey("***");                // 脱敏
        copy.setBaseUrl(entity.getBaseUrl());
        copy.setModelName(entity.getModelName());
        copy.setTemperature(entity.getTemperature());
        copy.setMaxTokens(entity.getMaxTokens());
        copy.setTimeoutSeconds(entity.getTimeoutSeconds());
        copy.setTopP(entity.getTopP());
        copy.setFrequencyPenalty(entity.getFrequencyPenalty());
        copy.setPresencePenalty(entity.getPresencePenalty());
        copy.setEmbeddingProvider(entity.getEmbeddingProvider());
        copy.setEmbeddingModel(entity.getEmbeddingModel());
        copy.setIsEnabled(entity.getIsEnabled());
        copy.setCreatedAt(entity.getCreatedAt());
        copy.setUpdatedAt(entity.getUpdatedAt());
        copy.setMaxContextTokens(entity.getMaxContextTokens());
        copy.setMaxOutputTokens(entity.getMaxOutputTokens());
        copy.setModelFamily(entity.getModelFamily());
        copy.setQuantization(entity.getQuantization());
        copy.setModelSizeGb(entity.getModelSizeGb());
        copy.setPromptPricePer1kTokens(entity.getPromptPricePer1kTokens());
        copy.setCompletionPricePer1kTokens(entity.getCompletionPricePer1kTokens());
        copy.setKnowledgeCutoffDate(entity.getKnowledgeCutoffDate());
        copy.setCapabilities(entity.getCapabilities());
        return copy;
    }

    /** 将数据库配置覆盖到 AiEnhancerProperties.llm 和 AiEnhancerProperties.rag */
    public void probeAndSaveModelCapabilities() {
        AiModelConfigEntity entity = getConfig();
        if (entity == null) {
            log.warn("[ai-model-config] 当前无启用的模型配置，跳过能力探测");
            return;
        }
        probeModelCapabilities(entity);
    }

    public void applyConfigToProperties(AiModelConfigEntity entity) {
        if (entity == null) return;
        AiEnhancerProperties.LlmConfig llm = properties.getLlm();
        if (entity.getProvider() != null) llm.setProvider(entity.getProvider());
        if (entity.getMaxTokens() != null) llm.setMaxTokens(entity.getMaxTokens());
        if (entity.getTimeoutSeconds() != null) llm.setTimeoutSeconds(entity.getTimeoutSeconds());
        if (entity.getTemperature() != null) llm.setTemperature(entity.getTemperature());

        // 根据 provider 选择具体子配置填充 baseUrl / modelName / apiKey
        String provider = entity.getProvider();
        if (provider == null) provider = "ollama";
        switch (provider) {
            case "ollama": {
                AiEnhancerProperties.OllamaConfig oc = llm.getOllama();
                if (oc == null) {
                    oc = new AiEnhancerProperties.OllamaConfig();
                    llm.setOllama(oc);
                }
                if (entity.getBaseUrl() != null) oc.setBaseUrl(entity.getBaseUrl());
                if (entity.getModelName() != null) oc.setModel(entity.getModelName());
                if (entity.getApiKey() != null) oc.setApiKey(entity.getApiKey());
                break;
            }
            case "openai-compatible":
            case "openai":
            case "deepseek": {
                AiEnhancerProperties.OpenAiCompatibleConfig oac = llm.getOpenaiCompatible();
                if (oac == null) {
                    oac = new AiEnhancerProperties.OpenAiCompatibleConfig();
                    llm.setOpenaiCompatible(oac);
                }
                if (entity.getApiKey() != null) oac.setApiKey(entity.getApiKey());
                if (entity.getBaseUrl() != null) oac.setBaseUrl(entity.getBaseUrl());
                if (entity.getModelName() != null) oac.setModel(entity.getModelName());
                break;
            }
            case "aliyun-bailian": {
                AiEnhancerProperties.AliyunBailianConfig abc = llm.getAliyunBailian();
                if (abc == null) {
                    abc = new AiEnhancerProperties.AliyunBailianConfig();
                    llm.setAliyunBailian(abc);
                }
                if (entity.getApiKey() != null) abc.setApiKey(entity.getApiKey());
                if (entity.getBaseUrl() != null) abc.setBaseUrl(entity.getBaseUrl());
                if (entity.getModelName() != null) abc.setModel(entity.getModelName());
                break;
            }
            default:
                // 未知 provider；仍然写入 ollama（保证下游 LlmProviderFactory 读到值）
                AiEnhancerProperties.OllamaConfig oc = llm.getOllama();
                if (oc == null) {
                    oc = new AiEnhancerProperties.OllamaConfig();
                    llm.setOllama(oc);
                }
                if (entity.getBaseUrl() != null) oc.setBaseUrl(entity.getBaseUrl());
                if (entity.getModelName() != null) oc.setModel(entity.getModelName());
                break;
        }

        // Embedding 子配置：写入 llm.embedding；同时对接到 rag.embeddingModel/rag.embeddingUrl（兼容旧代码路径）
        AiEnhancerProperties.EmbeddingConfig emb = llm.getEmbedding();
        if (emb == null) {
            emb = new AiEnhancerProperties.EmbeddingConfig();
            llm.setEmbedding(emb);
        }
        if (entity.getEmbeddingProvider() != null) emb.setProvider(entity.getEmbeddingProvider());
        if (entity.getEmbeddingModel() != null) emb.setModel(entity.getEmbeddingModel());
        AiEnhancerProperties.RagConfig rag = properties.getRag();
        if (rag != null) {
            if (entity.getEmbeddingModel() != null) rag.setEmbeddingModel(entity.getEmbeddingModel());
            // 若 ollama 作为 Embedding 提供者，同时更新 rag.embeddingUrl（EmbeddingService 默认走这里）
            String embProvider = entity.getEmbeddingProvider();
            if ("ollama".equals(embProvider) && entity.getBaseUrl() != null) {
                rag.setEmbeddingUrl(entity.getBaseUrl());
            } else if ("ollama".equals(embProvider)) {
                rag.setEmbeddingUrl(emb.getOllamaBaseUrl());
            } else if ("openai".equals(embProvider)) {
                rag.setEmbeddingUrl(emb.getOpenaiBaseUrl());
            }
            // Embedding 向量维度：从数据库恢复到 properties.rag.dimension
            if (entity.getEmbeddingDimension() != null && entity.getEmbeddingDimension() > 0) {
                rag.setDimension(entity.getEmbeddingDimension());
                log.info("[ai-model-config] 从数据库恢复 Embedding 维度：{}", entity.getEmbeddingDimension());
            }
        }
    }

    // ===== 工具：探测 Embedding 模型维度 =====

    /**
     * 使用指定 provider + model 发送一个简短文本的 embedding 请求，
     * 返回向量维度；调用失败或异常时返回 null。
     */
    private Integer probeEmbeddingDimension(String provider, String model, String baseUrl, String apiKey) {
        if (model == null || model.isBlank()) return null;
        String probeText = "dimension_check";
        try {
            String url;
            String bodyStr;
            if ("ollama".equals(provider)) {
                url = (baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl) + "/api/embeddings";
                bodyStr = String.format("{\"model\":\"%s\",\"prompt\":\"%s\"}",
                        escapeJson(model), escapeJson(probeText));
            } else if ("openai".equals(provider) || "openai-compatible".equals(provider) || "deepseek".equals(provider)) {
                url = (baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl) + "/embeddings";
                bodyStr = String.format("{\"model\":\"%s\",\"input\":\"%s\"}",
                        escapeJson(model), escapeJson(probeText));
            } else if ("aliyun-bailian".equals(provider)) {
                url = (baseUrl == null || baseUrl.isBlank() ? "https://dashscope.aliyuncs.com/api/v1" : baseUrl) + "/services/text-embedding/embeddings";
                bodyStr = String.format("{\"model\":\"%s\",\"input\":{\"texts\":[\"%s\"]}}",
                        escapeJson(model), escapeJson(probeText));
            } else {
                return null;
            }
            RestTemplate rt = buildRestTemplate(Math.max(60, properties.getLlm().getTimeoutSeconds()));
            HttpHeaders hdrs = new HttpHeaders();
            hdrs.setContentType(MediaType.APPLICATION_JSON);
            hdrs.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
            if (apiKey != null && !apiKey.isBlank()) {
                hdrs.set("Authorization", "Bearer " + apiKey);
            }
            HttpEntity<String> httpEntity = new HttpEntity<>(bodyStr, hdrs);
            String raw = rt.postForObject(url, httpEntity, String.class);
            if (raw == null) return null;
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(raw);
            // Ollama: { "embedding": [0.1, 0.2, ...] }
            JsonNode embNode = root.get("embedding");
            if (embNode != null && embNode.isArray()) return embNode.size();
            // OpenAI: { "data": [ { "embedding": [ ... ], "index": 0, "object": "embedding" } ] }
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isArray() && dataNode.size() > 0) {
                JsonNode firstEmb = dataNode.get(0).get("embedding");
                if (firstEmb != null && firstEmb.isArray()) return firstEmb.size();
            }
            // DashScope: { "output": { "embeddings": [ { "embedding": [ ... ] } ] } }
            JsonNode outputNode = root.get("output");
            if (outputNode != null) {
                JsonNode embsNode = outputNode.get("embeddings");
                if (embsNode != null && embsNode.isArray() && embsNode.size() > 0) {
                    JsonNode firstEmb = embsNode.get(0).get("embedding");
                    if (firstEmb != null && firstEmb.isArray()) return firstEmb.size();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[ai-model-config] Embedding 维度探测失败（provider={}, model={}）：{}",
                    provider, model, e.getMessage());
            return null;
        }
    }

    private static RestTemplate buildRestTemplate(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        return new RestTemplate(factory);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static final java.util.Map<String, Integer> DEFAULT_CONTEXT_TOKENS = java.util.Map.of(
            "llama3:latest", 8192,
            "llama3:8b", 8192,
            "llama3:70b", 8192,
            "qwen2:7b", 32768,
            "qwen2:72b", 32768,
            "deepseek-r1:8b", 131072,
            "deepseek-r1:70b", 131072
    );

    private void probeModelCapabilities(AiModelConfigEntity entity) {
        if (entity == null || entity.getProvider() == null) return;

        String provider = entity.getProvider().toLowerCase().trim();
        String modelName = entity.getModelName();
        String baseUrl = entity.getBaseUrl();

        if ("ollama".equals(provider)) {
            probeOllamaModelCapabilities(entity, modelName, baseUrl);
        } else {
            applyDefaultContextTokens(entity, modelName);
        }
    }

    private void probeOllamaModelCapabilities(AiModelConfigEntity entity, String modelName, String baseUrl) {
        if (modelName == null || modelName.isBlank()) return;

        try {
            String url = (baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl) + "/api/show";
            String bodyStr = String.format("{\"name\":\"%s\"}", escapeJson(modelName));

            RestTemplate rt = buildRestTemplate(Math.max(60, properties.getLlm().getTimeoutSeconds()));
            HttpHeaders hdrs = new HttpHeaders();
            hdrs.setContentType(MediaType.APPLICATION_JSON);
            hdrs.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
            HttpEntity<String> httpEntity = new HttpEntity<>(bodyStr, hdrs);

            String raw = rt.postForObject(url, httpEntity, String.class);
            if (raw == null) {
                applyDefaultContextTokens(entity, modelName);
                return;
            }

            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(raw);
            JsonNode modelInfo = root.get("model_info");

            if (modelInfo == null || !modelInfo.isObject()) {
                applyDefaultContextTokens(entity, modelName);
                return;
            }

            String contextLength = getTextValue(modelInfo, "llama.context_length");
            if (contextLength == null) {
                contextLength = getTextValue(modelInfo, "general.context_length");
            }
            Integer maxContextTokens = parseInteger(contextLength);
            if (maxContextTokens != null && maxContextTokens > 0) {
                entity.setMaxContextTokens(maxContextTokens);
                Integer maxOutputTokens = maxContextTokens / 4;
                entity.setMaxOutputTokens(maxOutputTokens);
            }

            String architecture = getTextValue(modelInfo, "general.architecture");
            if (architecture != null && !architecture.isBlank()) {
                entity.setModelFamily(architecture.trim());
            }

            String fileType = getTextValue(modelInfo, "general.file_type");
            if (fileType != null) {
                entity.setQuantization(mapFileTypeToQuantization(fileType));
            }

            String paramCount = getTextValue(modelInfo, "general.parameter_count");
            if (paramCount != null && !paramCount.isBlank()) {
                entity.setModelSizeGb(parseModelSize(paramCount));
            }

            if (entity.getMaxContextTokens() != null) {
                mapper.updateById(entity);
                log.info("[ai-model-config] Ollama 模型能力探测完成（model={}），maxContextTokens={}, modelFamily={}, quantization={}, modelSizeGb={}",
                        modelName, entity.getMaxContextTokens(), entity.getModelFamily(),
                        entity.getQuantization(), entity.getModelSizeGb());
            } else {
                applyDefaultContextTokens(entity, modelName);
            }
        } catch (Exception e) {
            log.warn("[ai-model-config] Ollama 模型能力探测失败（model={}），使用默认值：{}", modelName, e.getMessage());
            applyDefaultContextTokens(entity, modelName);
        }
    }

    private void applyDefaultContextTokens(AiModelConfigEntity entity, String modelName) {
        if (modelName == null) return;
        Integer defaultTokens = DEFAULT_CONTEXT_TOKENS.get(modelName.toLowerCase().trim());
        if (defaultTokens != null && entity.getMaxContextTokens() == null) {
            entity.setMaxContextTokens(defaultTokens);
            entity.setMaxOutputTokens(defaultTokens / 4);
            mapper.updateById(entity);
            log.info("[ai-model-config] 使用默认上下文长度（model={}），maxContextTokens={}", modelName, defaultTokens);
        }
    }

    private String getTextValue(JsonNode node, String key) {
        if (node == null || key == null) return null;
        JsonNode valueNode = node.get(key);
        return valueNode == null ? null : valueNode.asText(null);
    }

    private Integer parseInteger(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseModelSize(String paramCount) {
        if (paramCount == null || paramCount.isBlank()) return null;
        try {
            String clean = paramCount.trim().replace(",", "");
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([\\d.]+)\\s*([KMBT])?", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher matcher = pattern.matcher(clean);
            if (matcher.find()) {
                double num = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2);
                if (unit != null) {
                    switch (unit.toUpperCase()) {
                        case "K": num /= 1_000_000; break;
                        case "M": num /= 1_000; break;
                        case "B": break;
                        case "T": num *= 1_000; break;
                        default: break;
                    }
                }
                return BigDecimal.valueOf(num);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private String mapFileTypeToQuantization(String fileType) {
        if (fileType == null) return null;
        switch (fileType.trim()) {
            case "1": return "fp16";
            case "2": return "fp32";
            case "10": return "q4_0";
            case "11": return "q4_1";
            case "12": return "q5_0";
            case "13": return "q5_1";
            case "14": return "q8_0";
            case "15": return "q4_K_M";
            case "16": return "q4_K_S";
            case "17": return "q5_K_M";
            case "18": return "q5_K_S";
            case "19": return "q6_K";
            case "20": return "q8_K_M";
            case "21": return "q8_K_S";
            default: return "unknown";
        }
    }
}
