package com.swagger.ai.enhancer.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swagger.ai.enhancer.ai.config.AiEnhancerProperties;
import com.swagger.ai.enhancer.ai.dto.RagConfigDto;
import com.swagger.ai.enhancer.ai.prompt.PromptTemplateManager;
import com.swagger.ai.enhancer.ai.service.AiModelConfigService;
import com.swagger.ai.enhancer.ai.service.RagConfigService;
import com.swagger.ai.enhancer.ai.service.RagMetricsService;
import com.swagger.ai.enhancer.ai.skill.SkillService;
import com.swagger.ai.enhancer.ai.provider.LlmProvider;
import com.swagger.ai.enhancer.ai.provider.LlmProviderFactory;
import com.swagger.ai.enhancer.ai.rag.EmbeddingService;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider;
import com.swagger.ai.enhancer.ai.rag.VectorStoreProvider.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * AI 服务 REST 控制器。所有 AI 调用通过 {@link LlmProviderFactory} 获取的 {@link LlmProvider} 执行。
 *   POST /api/ai/complete-one          — 补全单个元素描述
 *   POST /api/ai/complete-all          — 补全 OpenAPI JSON 中所有缺失的 description
 *   POST /api/ai/generate-guide        — 生成面向开发者的 API 集成指南（Markdown）
 *   POST /api/ai/generate-spec         — 生成面向非技术读者的产品说明书（Markdown）
 *   POST /api/ai/generate-requirement  — 生成面向 SE/架构师的需求规格文档（Markdown）
 *   POST /api/ai/generate-delivery     — 生成面向客户技术团队/运维的交付与运维文档（Markdown）
 *   POST /api/ai/generate-testcases    — 生成面向测试工程师的测试用例文档（Markdown）
 *   GET  /api/ai/health                — 健康检查
 *
 * RAG 检索：生成方法在调用 LLM 前，先用 context/请求体构造查询文本，
 * 通过 EmbeddingService 取向量，再在对应 docType 的 collection 上检索 top-K 片段，
 * 注入 systemPrompt 后发送给 LLM。检索失败或无结果时降级为纯 LLM 生成。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final ObjectMapper objectMapper;
    private final LlmProviderFactory llmProviderFactory;
    private final PromptTemplateManager promptTemplateManager;
    private final AiEnhancerProperties properties;
    private final EmbeddingService embeddingService;
    private final VectorStoreProvider vectorStoreProvider;
    private final RagConfigService ragConfigService;
    private final RagMetricsService ragMetricsService;
    private final SkillService skillService;
    private final AiModelConfigService modelConfigService;

    public AiController(ObjectMapper objectMapper,
                        LlmProviderFactory llmProviderFactory,
                        PromptTemplateManager promptTemplateManager,
                        AiEnhancerProperties properties,
                        EmbeddingService embeddingService,
                        VectorStoreProvider vectorStoreProvider,
                        RagConfigService ragConfigService,
                        RagMetricsService ragMetricsService,
                        SkillService skillService,
                        AiModelConfigService modelConfigService) {
        this.objectMapper = objectMapper;
        this.llmProviderFactory = llmProviderFactory;
        this.promptTemplateManager = promptTemplateManager;
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.vectorStoreProvider = vectorStoreProvider;
        this.ragConfigService = ragConfigService;
        this.ragMetricsService = ragMetricsService;
        this.skillService = skillService;
        this.modelConfigService = modelConfigService;
    }

    // ==================== RAG 辅助方法 ====================

    /**
     * RAG 检索结果：
     *   - context：注入 Prompt 的知识库片段文本（已附相似度分数并过滤低分）
     *   - summary：文档末尾的知识库参考情况标注
     *   - hitHigh：是否存在高相关片段（相似度 >= 0.7）
     */
    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    private static class RagResult {
        private String context;
        private String summary;
        private boolean hitHigh;

        static RagResult empty() {
            return new RagResult("", "", false);
        }
    }

    /**
     * Tag 分组结果。将 OpenAPI JSON 按 Tag 分组后，每个组包含精简后的子 JSON。
     * 支持二次拆分：当单个 Tag 下的接口数量过多时，按 Token 量拆分为多个子组。
     */
    @lombok.Data
    private static class TagGroup {
        private String tagName;
        private int subGroupIndex;
        private int totalSubGroups;
        private String content;
        private String simplifiedContent;
        private String generatedContent;
    }

    /** 默认上下文长度（当数据库未配置时使用）。 */
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 131072;

    /** 低分过滤阈值：低于 0.4 的片段视为噪音，不注入 Prompt。 */
    private static final double LOW_SCORE_THRESHOLD = 0.4;
    /** 高相关阈值：>= 0.7 的片段视为重要参考。 */
    private static final double HIGH_SCORE_THRESHOLD = 0.7;

    /**
     * 在指定 docType 的知识库中进行检索，返回包含 ragContext 文本与 ragSummary 标注的 RagResult。
     *   - 片段附带相似度分数，并标注 ✅ 高相关 / ⚠️ 仅供参考
     *   - 相似度 < 0.4 的片段直接过滤
     *   - summary 用于在生成的 Markdown 文档末尾追加动态标注
     * 异常或无有效片段时返回 RagResult.empty()。
     */
    private RagResult retrieveRagContext(String docType, String queryText) {
        AiEnhancerProperties.RagConfig rag = properties.getRag();
        if (rag == null || !rag.isEnabled()) {
            return RagResult.empty();
        }
        if (queryText == null || queryText.isBlank()) {
            return RagResult.empty();
        }

        String collectionName;
        try {
            RagConfigDto configDto = ragConfigService.getConfigOrDefault(docType);
            if (configDto != null && configDto.getCollectionName() != null && !configDto.getCollectionName().isBlank()) {
                collectionName = configDto.getCollectionName().replace("-", "_");
            } else {
                collectionName = rag.getCollectionPrefix() + "_" + docType.replace("-", "_");
            }
        } catch (Exception e) {
            log.warn("[RAG] 读取 docType={} 的自定义 collectionName 失败，降级使用默认公式: {}", docType, e.getMessage());
            collectionName = rag.getCollectionPrefix() + "_" + docType.replace("-", "_");
        }

        try {
            List<Double> queryVector = embeddingService.embed(queryText);
            List<SearchResult> results = vectorStoreProvider.search(
                    collectionName, queryVector, rag.getTopK(), rag.getSimilarityThreshold());
            if (results == null || results.isEmpty()) {
                log.debug("[RAG] docType={}, collection={}, 无命中片段", docType, collectionName);
                // 仍累计检索计数（totalRetrievals），方便指标接口展示 0 命中率
                recordMetricsSafely(docType, false, 0.0, 0, 0, 0);
                return RagResult.empty();
            }

            StringBuilder sb = new StringBuilder();
            int i = 1;
            int highCount = 0;
            int mediumCount = 0;
            int lowCount = 0;
            double highestScore = 0.0;

            for (SearchResult r : results) {
                if (r == null || r.getContent() == null) continue;
                double score = r.getScore();
                if (score > highestScore) highestScore = score;
                if (score < LOW_SCORE_THRESHOLD) {
                    lowCount++;
                    continue; // 低分片段不注入 Prompt
                }
                sb.append("[").append(i++).append("] [相似度: ")
                        .append(String.format("%.2f", score)).append("] ");
                if (score >= HIGH_SCORE_THRESHOLD) {
                    sb.append("[✅ 优先参考]");
                    highCount++;
                } else {
                    sb.append("[⚠️ 仅供参考]");
                    mediumCount++;
                }
                sb.append("\n").append(r.getContent().replace("\n", " ")).append("\n");
            }

            String ragContext = sb.toString().trim();

            String summary;
            if (highCount > 0) {
                summary = String.format(
                        "本文档参考了知识库中 %d 条高相关片段，%d 条中相关片段仅供参考，%d 条低相关片段已自动过滤。",
                        highCount, mediumCount, lowCount);
            } else if (mediumCount > 0) {
                summary = String.format(
                        "本文档参考了知识库中 %d 条中相关片段仅供参考，%d 条低相关片段已自动过滤，未命中高相关片段。",
                        mediumCount, lowCount);
            } else {
                summary = "⚠️ 本文档未参考知识库内容，可能缺少业务细节。如需更准确的文档，请先在 RAG 设置中同步相关业务资料后重新生成。";
            }

            // 累计检索指标
            recordMetricsSafely(docType, highCount > 0, highestScore, highCount, mediumCount, lowCount);

            if (ragContext.isBlank()) {
                // 所有片段都被过滤或为空，仍返回 summary 告知用户
                return new RagResult("", summary, highCount > 0);
            }
            log.debug("[RAG] docType={}, collection={}, 命中 {} 片段（高={}，中={}，低={}，最高={:.2f}）",
                    docType, collectionName, results.size(), highCount, mediumCount, lowCount, highestScore);
            return new RagResult(ragContext, summary, highCount > 0);
        } catch (Exception e) {
            log.warn("[RAG] 检索失败（docType={}, collection={}）：{}",
                    docType, collectionName, e.getMessage());
            // 失败也计入检索总数，以便指标反映实际调用次数
            recordMetricsSafely(docType, false, 0.0, 0, 0, 0);
            return RagResult.empty();
        }
    }

    /**
     * 安全地调用 RagMetricsService 记录检索数据。若 Bean 未装配，静默跳过，不阻塞主流程。
     */
    private void recordMetricsSafely(String docType, boolean hitHigh, double highestScore,
                                     int highCount, int mediumCount, int lowCount) {
        if (ragMetricsService == null) {
            return;
        }
        try {
            ragMetricsService.recordRetrieval(docType, hitHigh, highestScore, highCount, mediumCount, lowCount);
        } catch (Exception ex) {
            log.warn("[RAG] 记录检索指标失败（docType={}）：{}", docType, ex.getMessage());
        }
    }

    /** 安全加载指定 docType 的 Skill 文档内容；异常时返回空字符串，不阻塞生成。 */
    private String safeLoadSkill(String docType) {
        if (skillService == null) {
            return "";
        }
        try {
            return skillService.loadSkillContext(docType);
        } catch (Exception e) {
            log.warn("[skill] 加载 docType={} 失败，降级为空: {}", docType, e.getMessage());
            return "";
        }
    }

    /**
     * 从 complete-one 请求 body 中提取可用于检索的自然语言 query 文本。
     */
    private static String buildQueryFromCompleteOne(Map<String, Object> body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        Object et = body.get("elementType");
        if (et != null) sb.append("elementType=").append(et).append("; ");
        Object path = body.get("path");
        if (path != null) sb.append("path=").append(path).append("; ");
        Object method = body.get("method");
        if (method != null) sb.append("method=").append(method).append("; ");
        Object context = body.get("context");
        if (context instanceof Map<?, ?> cmap && !cmap.isEmpty()) {
            for (Map.Entry<?, ?> e : cmap.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append("; ");
            }
        } else if (context != null) {
            sb.append(context);
        }
        return sb.toString().trim();
    }

    /**
     * 从完整的 OpenAPI JSON 中提取简短 summary 文本，用于 RAG 查询。
     * 策略：标题、信息块 description、paths 列表。
     */
    private String buildQueryFromOpenApi(String openApiJson, int maxLen) {
        if (openApiJson == null || openApiJson.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(openApiJson);
            StringBuilder sb = new StringBuilder();
            JsonNode info = root.get("info");
            if (info != null) {
                if (info.has("title")) sb.append("title=").append(info.get("title").asText()).append("; ");
                if (info.has("description")) sb.append("desc=").append(info.get("description").asText()).append("; ");
            }
            JsonNode paths = root.get("paths");
            if (paths != null && paths.isObject()) {
                int count = 0;
                Iterator<String> it = paths.fieldNames();
                while (it.hasNext() && count < 15) {
                    String name = it.next();
                    if (isInternalPath(name)) continue;
                    sb.append(name).append(", ");
                    count++;
                }
            }
            String q = sb.toString().trim();
            if (q.length() > maxLen) q = q.substring(0, maxLen);
            return q;
        } catch (Exception ignored) {
            // 若解析失败，截取前缀作为 fallback 查询文本
            if (openApiJson.length() > maxLen) return openApiJson.substring(0, maxLen);
            return openApiJson;
        }
    }

    private static boolean isInternalPath(String path) {
        if (path == null) return false;
        String[] internalPrefixes = {
                "/api/ai/",
                "/v3/api-docs-enhanced"
        };
        for (String prefix : internalPrefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 内部 Controller 的 Tag 名称列表（类名转 kebab-case），用于过滤 tags 节点。 */
    private static final List<String> INTERNAL_TAG_NAMES = Arrays.asList(
            "enhanced-open-api-controller",
            "ai-controller",
            "ai-rag-controller",
            "ai-settings-controller",
            "ai-model-config-controller"
    );

    /** 内部实体类的 Schema 名称列表，用于过滤 components.schemas 节点。 */
    private static final List<String> INTERNAL_SCHEMA_NAMES = Arrays.asList(
            "AiModelConfigEntity",
            "RagConfigDto",
            "RagSyncMetadataEntity"
    );

    private String filterInternalPaths(String openApiJson) {
        if (openApiJson == null || openApiJson.isBlank()) return openApiJson;
        try {
            JsonNode root = objectMapper.readTree(openApiJson);
            // 1. 过滤 paths 节点中的内部路径
            if (root.has("paths") && root.get("paths").isObject()) {
                ObjectNode pathsNode = (ObjectNode) root.get("paths");
                Iterator<String> it = pathsNode.fieldNames();
                List<String> internalPaths = new ArrayList<>();
                while (it.hasNext()) {
                    String path = it.next();
                    if (isInternalPath(path)) {
                        internalPaths.add(path);
                    }
                }
                for (String path : internalPaths) {
                    pathsNode.remove(path);
                }
            }
            // 2. 过滤 tags 节点中的内部 Controller Tag
            if (root.has("tags") && root.get("tags").isArray()) {
                ArrayNode tagsNode = (ArrayNode) root.get("tags");
                Iterator<JsonNode> tagIt = tagsNode.iterator();
                while (tagIt.hasNext()) {
                    JsonNode tag = tagIt.next();
                    if (tag.has("name") && INTERNAL_TAG_NAMES.contains(tag.get("name").asText())) {
                        tagIt.remove();
                    }
                }
            }
            // 3. 过滤 components.schemas 节点中的内部实体类
            if (root.has("components") && root.get("components").isObject()
                    && root.get("components").has("schemas")
                    && root.get("components").get("schemas").isObject()) {
                ObjectNode schemasNode = (ObjectNode) root.get("components").get("schemas");
                for (String name : INTERNAL_SCHEMA_NAMES) {
                    schemasNode.remove(name);
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("过滤内部路径失败，使用原始 JSON: {}", e.getMessage());
            return openApiJson;
        }
    }

    // ==================== POST /api/ai/complete-one ====================

    @PostMapping(value = "/complete-one",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> completeOne(@RequestBody Map<String, Object> body) {
        long start = System.currentTimeMillis();
        log.info("complete-one: elementType={}, path={}, method={}",
                body == null ? null : body.get("elementType"),
                body == null ? null : body.get("path"),
                body == null ? null : body.get("method"));

        try {
            // 先做 RAG 检索（docType="api"），注入 Prompt；同时加载 Skill 文档
            String elementType = body == null ? null : (String) body.get("elementType");
            boolean isParameter = "parameter".equals(elementType);
            String queryText = buildQueryFromCompleteOne(body);
            RagResult rag = retrieveRagContext("api", queryText);
            String skillContext = safeLoadSkill(isParameter ? "api-parameter" : "api-doc");

            PromptTemplateManager.Template template;
            String parameterName = null;
            if (isParameter) {
                Object ctx = body == null ? null : body.get("context");
                Map<String, Object> ctxMap = ctx instanceof Map ? castMap(ctx) : new LinkedHashMap<>();
                Object pn = ctxMap.get("parameterName");
                Object pt = ctxMap.get("parameterType");
                Object pin = ctxMap.get("parameterIn");
                parameterName = pn == null ? null : pn.toString();
                Map<String, Object> openApiCtx = new LinkedHashMap<>();
                openApiCtx.put("path", body.get("path"));
                openApiCtx.put("method", body.get("method"));
                openApiCtx.put("summary", ctxMap.get("operationSummary"));
                openApiCtx.put("parameterName", parameterName);
                openApiCtx.put("parameterType", pt == null ? "" : pt.toString());
                openApiCtx.put("parameterIn", pin == null ? "" : pin.toString());
                template = promptTemplateManager.buildCompleteParameterPrompt(
                        openApiCtx, rag.getContext(), rag.getSummary(), skillContext,
                        parameterName, pt == null ? null : pt.toString(), pin == null ? null : pin.toString());
            } else {
                template = promptTemplateManager.buildCompleteOnePrompt(body, rag.getContext(), rag.getSummary(), skillContext);
            }

            LlmProvider llm = llmProviderFactory.getProvider();
            String rawText = llm.generate(template.getSystemPrompt(), template.getUserPrompt());
            log.info("[DEBUG-complete-one-raw] elementType={}, path={}, method={}, rawText.length={}, rawText={}",
                    elementType, body == null ? null : body.get("path"), body == null ? null : body.get("method"),
                    rawText != null ? rawText.length() : 0, rawText);

            // 容错清理：去除 LLM 可能附加的常见引导语前缀
            if (rawText != null) {
                String cleaned = rawText.trim();
                // 常见引导语前缀（按长度从长到短匹配，避免误删）
                String[] prefixesToRemove = {
                        "根据提供的API接口信息，该接口适合的描述为：",
                        "根据提供的API信息，该接口适合的描述为：",
                        "根据提供的API信息，该接口的描述为：",
                        "根据提供的API接口信息，该接口的描述为：",
                        "根据提供的 API 接口信息，该接口适合的描述为：",
                        "根据提供的 API 信息，该接口的描述为：",
                        "该接口适合的描述为：",
                        "该接口的描述为：",
                        "该参数的描述为：",
                        "该参数描述为：",
                        "参数描述：",
                        "参数描述:",
                        "接口描述：",
                        "接口描述:",
                        "描述：",
                        "描述:"
                };
                for (String prefix : prefixesToRemove) {
                    if (cleaned.startsWith(prefix)) {
                        cleaned = cleaned.substring(prefix.length()).trim();
                        break;
                    }
                }
                // 去除末尾可能的多余标点或空白
                cleaned = cleaned.replaceAll("\\s+$", "");
                rawText = cleaned;
            }

            Map<String, Object> response = new LinkedHashMap<>();
            Map<String, Object> descriptions = new LinkedHashMap<>();

            Object parsed = safeParseToJsonOrRaw(rawText);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsedMap = (Map<String, Object>) parsed;
                if (parsedMap.containsKey("descriptions")) {
                    // 下钻提取：避免嵌套成 {descriptions: {descriptions: {...}}}
                    Object inner = parsedMap.get("descriptions");
                    if (inner instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> innerMap = (Map<String, Object>) inner;
                        descriptions.putAll(innerMap);
                    } else if (inner != null) {
                        descriptions.put("operation", inner.toString());
                    }
                } else {
                    descriptions.putAll(parsedMap);
                }
            } else {
                if (isParameter) {
                    // 参数补全：只填充 parameters 字段，不写 operation
                    if (parameterName != null && !parameterName.isBlank()) {
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put(parameterName, rawText);
                        descriptions.put("parameters", params);
                    } else {
                        // 参数名获取失败时兜底：写入 operation（前端仍然能显示）
                        descriptions.put("operation", rawText);
                    }
                } else {
                    String key = elementType == null || elementType.isBlank() ? "operation" : elementType;
                    descriptions.put(key, rawText);
                }
            }

            response.put("descriptions", descriptions);
            response.put("ragHit", !rag.getContext().isBlank());
            response.put("ragSummary", rag.getSummary());
            long cost = System.currentTimeMillis() - start;
            log.info("complete-one done: elementType={}, cost={}ms, ragHit={}",
                    elementType, cost, !rag.getContext().isBlank());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("complete-one failed, elementType={}, cost={}ms: {}",
                    body == null ? null : body.get("elementType"), cost, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return (Map<String, Object>) obj;
    }

    // ==================== POST /api/ai/complete-all ====================

    @PostMapping(value = "/complete-all",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> completeAll(@RequestBody String openApiJson) {
        long start = System.currentTimeMillis();
        log.info("complete-all: openApiJson length={}", openApiJson == null ? 0 : openApiJson.length());

        JsonNode root;
        try {
            root = objectMapper.readTree(openApiJson == null ? "" : openApiJson);
        } catch (Exception e) {
            log.error("complete-all: 输入非合法 JSON: {}", e.getMessage());
            return ResponseEntity.status(400).body(toJson(new ErrorResponse("输入非合法 JSON", e.getMessage())));
        }

        try {
            String queryText = buildQueryFromOpenApi(openApiJson, 800);
            RagResult rag = retrieveRagContext("api", queryText);
            String skillContext = safeLoadSkill("api-doc");
            PromptTemplateManager.Template template = promptTemplateManager.buildCompleteAllPrompt(openApiJson, rag.getContext(), rag.getSummary(), skillContext);

            LlmProvider llm = llmProviderFactory.getProvider();
            String llmRaw = llm.generate(template.getSystemPrompt(), template.getUserPrompt());

            JsonNode llmNode = safeParseJsonNode(llmRaw);
            JsonNode descriptionsNode = null;
            if (llmNode != null && llmNode.has("descriptions")) {
                descriptionsNode = llmNode.get("descriptions");
            } else if (llmNode != null) {
                descriptionsNode = llmNode;
            }

            if (descriptionsNode != null && descriptionsNode.isObject()) {
                ObjectNode merged = (ObjectNode) root.deepCopy();
                mergeDescriptions(merged, descriptionsNode);
                merged.put("x-rag-hit", !rag.getContext().isBlank());
                merged.put("x-rag-summary", rag.getSummary());
                long cost = System.currentTimeMillis() - start;
                log.info("complete-all done: cost={}ms, ragHit={}, merged={} descriptions",
                        cost, !rag.getContext().isBlank(), descriptionsNode.size());
                return ResponseEntity.ok(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(merged));
            }

            long cost = System.currentTimeMillis() - start;
            log.warn("complete-all: LLM 返回非合法 JSON（{} ms），降级返回原始 JSON", cost);
            ((ObjectNode) root).put("x-rag-hit", !rag.getContext().isBlank());
            ((ObjectNode) root).put("x-rag-summary", rag.getSummary());
            ((ObjectNode) root).put("x-note", "LLM 未返回结构化 descriptions，已降级为原始 JSON");
            return ResponseEntity.ok(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("complete-all failed, cost={}ms: {}", cost, e.getMessage());
            return ResponseEntity.ok(openApiJson);
        }
    }

    // ==================== POST /api/ai/generate-guide ====================

    /**
     * 生成面向开发者的集成指南（Markdown）。
     * 检索时 docType = "integration-guide"。
     */
    @PostMapping(value = "/generate-guide",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> generateGuide(@RequestBody String openApiJson) {
        return generateDocumentWithGroups(openApiJson, "generate-guide",
                "integration-guide", "integration-guide", "集成指南", "Integration Guide",
                this::generateGuideLegacy);
    }

    private ResponseEntity<String> generateGuideLegacy(String openApiJson) {
        long start = System.currentTimeMillis();
        log.info("generate-guide-legacy: openApiJson length={}", openApiJson == null ? 0 : openApiJson.length());

        try {
            String queryText = buildQueryFromOpenApi(openApiJson, 800);
            RagResult rag = retrieveRagContext("integration-guide", queryText);
            String skillContext = safeLoadSkill("integration-guide");
            String filteredOpenApiJson = filterInternalPaths(openApiJson);
            PromptTemplateManager.Template template = promptTemplateManager.buildGenerateGuidePrompt(filteredOpenApiJson, rag.getContext(), rag.getSummary(), skillContext);

            LlmProvider llm = llmProviderFactory.getProvider();
            String markdown = llm.generate(template.getSystemPrompt(), template.getUserPrompt());

            long cost = System.currentTimeMillis() - start;
            log.info("generate-guide-legacy done: cost={}ms, ragHit={}",
                    cost, !rag.getContext().isBlank());
            String cleaned = stripMarkdownCodeFence(markdown);
            String finalDoc = appendRagSummary(cleaned, rag.getSummary());
            return ResponseEntity.ok(finalDoc);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("generate-guide-legacy failed, cost={}ms: {}", cost, e.getMessage());
            return ResponseEntity.status(500).body("生成集成指南失败：" + e.getMessage());
        }
    }

    // ==================== POST /api/ai/generate-spec ====================

    /**
     * 生成面向非技术读者的产品说明书（Markdown）。
     * 检索时 docType = "product-doc"。
     */
    @PostMapping(value = "/generate-spec",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> generateSpec(@RequestBody String openApiJson) {
        return generateDocumentWithGroups(openApiJson, "generate-spec",
                "product-doc", "product-doc", "产品说明书", "Product Specification",
                this::generateSpecLegacy);
    }

    private ResponseEntity<String> generateSpecLegacy(String openApiJson) {
        long start = System.currentTimeMillis();
        log.info("generate-spec-legacy: openApiJson length={}", openApiJson == null ? 0 : openApiJson.length());

        try {
            String queryText = buildQueryFromOpenApi(openApiJson, 800);
            RagResult rag = retrieveRagContext("product-doc", queryText);
            String skillContext = safeLoadSkill("product-doc");
            String filteredOpenApiJson = filterInternalPaths(openApiJson);
            PromptTemplateManager.Template template = promptTemplateManager.buildGenerateSpecPrompt(filteredOpenApiJson, rag.getContext(), rag.getSummary(), skillContext);

            LlmProvider llm = llmProviderFactory.getProvider();
            String markdown = llm.generate(template.getSystemPrompt(), template.getUserPrompt());

            long cost = System.currentTimeMillis() - start;
            log.info("generate-spec-legacy done: cost={}ms, ragHit={}",
                    cost, !rag.getContext().isBlank());
            String cleaned = stripMarkdownCodeFence(markdown);
            String finalDoc = appendRagSummary(cleaned, rag.getSummary());
            return ResponseEntity.ok(finalDoc);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("generate-spec-legacy failed, cost={}ms: {}", cost, e.getMessage());
            return ResponseEntity.status(500).body("生成产品说明书失败：" + e.getMessage());
        }
    }

    // ==================== POST /api/ai/generate-requirement ====================

    /**
     * 生成面向 SE/架构师的需求规格文档（Markdown）。
     * 检索时 docType = "requirement-doc"。
     */
    @PostMapping(value = "/generate-requirement",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> generateRequirement(@RequestBody String openApiJson) {
        return generateDocumentWithGroups(openApiJson, "generate-requirement",
                "requirement-doc", "requirement-doc", "需求文档", "Requirements Document",
                this::generateRequirementLegacy);
    }

    private ResponseEntity<String> generateRequirementLegacy(String openApiJson) {
        long start = System.currentTimeMillis();
        log.info("generate-requirement-legacy: openApiJson length={}", openApiJson == null ? 0 : openApiJson.length());

        try {
            String queryText = buildQueryFromOpenApi(openApiJson, 800);
            RagResult rag = retrieveRagContext("requirement-doc", queryText);
            String skillContext = safeLoadSkill("requirement-doc");
            String filteredOpenApiJson = filterInternalPaths(openApiJson);
            PromptTemplateManager.Template template = promptTemplateManager.buildRequirementDocPrompt(filteredOpenApiJson, rag.getContext(), rag.getSummary(), skillContext);

            LlmProvider llm = llmProviderFactory.getProvider();
            String markdown = llm.generate(template.getSystemPrompt(), template.getUserPrompt());

            long cost = System.currentTimeMillis() - start;
            log.info("generate-requirement-legacy done: cost={}ms, ragHit={}",
                    cost, !rag.getContext().isBlank());
            String cleaned = stripMarkdownCodeFence(markdown);
            String finalDoc = appendRagSummary(cleaned, rag.getSummary());
            return ResponseEntity.ok(finalDoc);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("generate-requirement-legacy failed, cost={}ms: {}", cost, e.getMessage());
            return ResponseEntity.status(500).body("生成需求文档失败：" + e.getMessage());
        }
    }

    // ==================== POST /api/ai/generate-delivery ====================

    /**
     * 生成面向客户技术团队/运维的交付与运维文档（Markdown）。
     * 检索时 docType = "delivery-doc"。
     */
    @PostMapping(value = "/generate-delivery",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> generateDelivery(@RequestBody String openApiJson) {
        return generateDocumentWithGroups(openApiJson, "generate-delivery",
                "delivery-doc", "delivery-doc", "交付文档", "Delivery Document",
                this::generateDeliveryLegacy);
    }

    private ResponseEntity<String> generateDeliveryLegacy(String openApiJson) {
        long start = System.currentTimeMillis();
        log.info("generate-delivery-legacy: openApiJson length={}", openApiJson == null ? 0 : openApiJson.length());

        try {
            String queryText = buildQueryFromOpenApi(openApiJson, 800);
            RagResult rag = retrieveRagContext("delivery-doc", queryText);
            String skillContext = safeLoadSkill("delivery-doc");
            String filteredOpenApiJson = filterInternalPaths(openApiJson);
            PromptTemplateManager.Template template = promptTemplateManager.buildDeliveryDocPrompt(filteredOpenApiJson, rag.getContext(), rag.getSummary(), skillContext);

            LlmProvider llm = llmProviderFactory.getProvider();
            String markdown = llm.generate(template.getSystemPrompt(), template.getUserPrompt());

            long cost = System.currentTimeMillis() - start;
            log.info("generate-delivery-legacy done: cost={}ms, ragHit={}",
                    cost, !rag.getContext().isBlank());
            String cleaned = stripMarkdownCodeFence(markdown);
            String finalDoc = appendRagSummary(cleaned, rag.getSummary());
            return ResponseEntity.ok(finalDoc);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("generate-delivery-legacy failed, cost={}ms: {}", cost, e.getMessage());
            return ResponseEntity.status(500).body("生成交付文档失败：" + e.getMessage());
        }
    }

    // ==================== POST /api/ai/generate-testcases ====================

    /**
     * 生成面向测试工程师的测试用例文档（Markdown）。
     * 检索时 docType = "testcase-doc"。
     */
    @PostMapping(value = "/generate-testcases",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> generateTestCases(@RequestBody String openApiJson) {
        return generateDocumentWithGroups(openApiJson, "generate-testcases",
                "testcase-doc", "testcase-doc", "测试用例", "Test Cases",
                this::generateTestCasesLegacy);
    }

    private ResponseEntity<String> generateTestCasesLegacy(String openApiJson) {
        long start = System.currentTimeMillis();
        log.info("generate-testcases-legacy: openApiJson length={}", openApiJson == null ? 0 : openApiJson.length());

        try {
            String queryText = buildQueryFromOpenApi(openApiJson, 800);
            RagResult rag = retrieveRagContext("testcase-doc", queryText);
            String skillContext = safeLoadSkill("testcase-doc");
            String filteredOpenApiJson = filterInternalPaths(openApiJson);
            PromptTemplateManager.Template template = promptTemplateManager.buildTestCaseDocPrompt(filteredOpenApiJson, rag.getContext(), rag.getSummary(), skillContext);

            LlmProvider llm = llmProviderFactory.getProvider();
            String markdown = llm.generate(template.getSystemPrompt(), template.getUserPrompt());

            long cost = System.currentTimeMillis() - start;
            log.info("generate-testcases-legacy done: cost={}ms, ragHit={}",
                    cost, !rag.getContext().isBlank());
            String cleaned = stripMarkdownCodeFence(markdown);
            String finalDoc = appendRagSummary(cleaned, rag.getSummary());
            return ResponseEntity.ok(finalDoc);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("generate-testcases-legacy failed, cost={}ms: {}", cost, e.getMessage());
            return ResponseEntity.status(500).body("生成测试用例失败：" + e.getMessage());
        }
    }

    // ==================== GET /api/ai/health ====================

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        try {
            result.put("provider", llmProviderFactory.getProvider().getProviderName());
        } catch (Exception e) {
            result.put("provider", "unknown");
        }
        return ResponseEntity.ok(result);
    }

    // ==================== 辅助方法 ====================

    /**
     * 将知识库参考情况标注追加到 Markdown 文档末尾。
     */
    private String appendRagSummary(String doc, String summary) {
        if (doc == null) return "";
        if (summary == null || summary.isBlank()) return doc;
        return doc + "\n\n---\n\n📊 " + summary;
    }

    private Object safeParseToJsonOrRaw(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        String cleaned = stripMarkdownCodeFence(trimmed);
        if (cleaned.startsWith("{") || cleaned.startsWith("[")) {
            try {
                return objectMapper.readValue(cleaned, Object.class);
            } catch (Exception ignored) {
                // 解析失败，返回原文
            }
        }
        return text.trim();
    }

    private JsonNode safeParseJsonNode(String text) {
        if (text == null) return null;
        String cleaned = stripMarkdownCodeFence(text.trim());
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripMarkdownCodeFence(String text) {
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            if (firstLine < 0) return text;
            int end = text.lastIndexOf("```");
            if (end <= firstLine) {
                return text.substring(firstLine + 1).trim();
            }
            return text.substring(firstLine + 1, end).trim();
        }
        return text;
    }

    /**
     * 把 LLM 返回的 descriptions 合并回原始 OpenAPI JSON。
     * 键名约定：路径元素之间使用 '|' 分隔，例如：
     *   "paths|{path}|{method}" -> paths.{path}.{method}.description
     *   "paths|{path}|{method}|parameters|{name}" -> 在参数数组中匹配 name 的 description
     *   "components|schemas|{schema}|properties|{field}" -> components.schemas.{schema}.properties.{field}.description
     *   "tags|{name}" -> 在 tags 数组中匹配 name 的 description
     */
    private static void mergeDescriptions(ObjectNode root, JsonNode descriptions) {
        Iterator<Map.Entry<String, JsonNode>> fields = descriptions.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            String description = entry.getValue().isTextual()
                    ? entry.getValue().asText()
                    : entry.getValue().toString();

            try {
                applyDescriptionByKey(root, key, description);
            } catch (Exception ex) {
                log.warn("合并 description 失败，key={}: {}", key, ex.getMessage());
            }
        }
    }

    private static void applyDescriptionByKey(ObjectNode root, String key, String description) {
        String[] parts = key.split("\\|");
        if (parts.length == 0) return;
        JsonNode current = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (current == null || !current.isObject()) return;
            if ("parameters".equals(part) && i + 1 < parts.length) {
                String paramName = parts[++i];
                current = findNamedItemInArray(current.get(part), paramName);
            } else if ("tags".equals(part) && current.has("tags") && current.get("tags").isArray()) {
                String tagName = parts[++i];
                current = findNamedItemInArray(current.get("tags"), tagName);
            } else {
                current = current.get(part);
            }
        }
        if (current instanceof ObjectNode obj) {
            obj.put("description", description);
            obj.put("x-ai-generated", true);
        }
    }

    private static JsonNode findNamedItemInArray(JsonNode arrayNode, String name) {
        if (arrayNode == null || !arrayNode.isArray()) return null;
        for (JsonNode item : arrayNode) {
            if (item.has("name") && item.get("name").isTextual()
                    && name.equals(item.get("name").asText())) {
                return item;
            }
        }
        return null;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败: " + e.getMessage() + "\"}";
        }
    }

    // ==================== 通用文档生成方法 ====================

    /**
     * 估算文本的 Token 数。简单估算：字符数 / 2。
     */
    private int estimateTokens(String json) {
        if (json == null || json.isBlank()) return 0;
        return json.length() / 2;
    }

    /**
     * 获取当前模型的最大上下文 Token 数。从数据库读取，若未配置则使用默认值。
     */
    private int getMaxContextTokens() {
        if (modelConfigService == null) {
            return DEFAULT_MAX_CONTEXT_TOKENS;
        }
        try {
            var config = modelConfigService.getConfig();
            if (config != null && config.getMaxContextTokens() != null && config.getMaxContextTokens() > 0) {
                return config.getMaxContextTokens();
            }
        } catch (Exception e) {
            log.warn("[split] 读取模型配置失败，使用默认上下文长度: {}", e.getMessage());
        }
        return DEFAULT_MAX_CONTEXT_TOKENS;
    }

    /**
     * 获取模型最大上下文 Token 数。
     * 优先从数据库读取已探测值，如果没有则主动探测一次并写入数据库。
     * 探测失败时返回保守默认值 4000 tokens。
     */
    private int getOrProbeMaxContextTokens() {
        if (modelConfigService == null) {
            return DEFAULT_MAX_CONTEXT_TOKENS;
        }
        try {
            var config = modelConfigService.getConfig();
            if (config != null && config.getMaxContextTokens() != null && config.getMaxContextTokens() > 0) {
                return config.getMaxContextTokens();
            }
            log.info("[ai] 未探测到模型上下文限制，开始主动探测...");
            modelConfigService.probeAndSaveModelCapabilities();
            config = modelConfigService.getConfig();
            if (config != null && config.getMaxContextTokens() != null && config.getMaxContextTokens() > 0) {
                log.info("[ai] 主动探测完成，maxContextTokens={}", config.getMaxContextTokens());
                return config.getMaxContextTokens();
            }
        } catch (Exception e) {
            log.warn("[ai] 主动探测模型上下文限制失败: {}", e.getMessage());
        }
        log.warn("[ai] 使用默认上下文限制 {} tokens", DEFAULT_MAX_CONTEXT_TOKENS);
        return DEFAULT_MAX_CONTEXT_TOKENS;
    }

    /**
     * 对 OpenAPI JSON 进行结构精简：
     * - Schema 定义只保留对象名、字段名和字段类型
     * - 保留所有 paths 的完整内容
     * - 不做路径前缀合并
     */
    private String simplifyOpenApiJson(String openApiJson) {
        if (openApiJson == null || openApiJson.isBlank()) return openApiJson;
        try {
            JsonNode root = objectMapper.readTree(openApiJson);
            ObjectNode simplified = objectMapper.createObjectNode();

            if (root.has("openapi")) simplified.set("openapi", root.get("openapi"));
            if (root.has("info")) simplified.set("info", root.get("info"));
            if (root.has("tags")) simplified.set("tags", root.get("tags"));
            if (root.has("paths")) simplified.set("paths", root.get("paths"));

            if (root.has("components") && root.get("components").isObject()) {
                ObjectNode components = objectMapper.createObjectNode();
                JsonNode schemas = root.get("components").get("schemas");
                if (schemas != null && schemas.isObject()) {
                    ObjectNode simplifiedSchemas = objectMapper.createObjectNode();
                    Iterator<Map.Entry<String, JsonNode>> schemaIt = schemas.fields();
                    while (schemaIt.hasNext()) {
                        Map.Entry<String, JsonNode> schemaEntry = schemaIt.next();
                        String schemaName = schemaEntry.getKey();
                        JsonNode schema = schemaEntry.getValue();
                        ObjectNode simplifiedSchema = objectMapper.createObjectNode();

                        if (schema.has("type")) simplifiedSchema.set("type", schema.get("type"));
                        if (schema.has("properties") && schema.get("properties").isObject()) {
                            ObjectNode simplifiedProps = objectMapper.createObjectNode();
                            Iterator<Map.Entry<String, JsonNode>> propIt = schema.get("properties").fields();
                            while (propIt.hasNext()) {
                                Map.Entry<String, JsonNode> propEntry = propIt.next();
                                String propName = propEntry.getKey();
                                JsonNode prop = propEntry.getValue();
                                ObjectNode simplifiedProp = objectMapper.createObjectNode();
                                if (prop.has("type")) simplifiedProp.set("type", prop.get("type"));
                                simplifiedProps.set(propName, simplifiedProp);
                            }
                            simplifiedSchema.set("properties", simplifiedProps);
                        }
                        if (schema.has("required") && schema.get("required").isArray()) {
                            simplifiedSchema.set("required", schema.get("required"));
                        }
                        simplifiedSchemas.set(schemaName, simplifiedSchema);
                    }
                    components.set("schemas", simplifiedSchemas);
                }
                simplified.set("components", components);
            }

            return objectMapper.writeValueAsString(simplified);
        } catch (Exception e) {
            log.warn("[simplify] 精简 OpenAPI JSON 失败，使用原始数据: {}", e.getMessage());
            return openApiJson;
        }
    }

    /**
     * 按 Tag 分组 OpenAPI JSON，每个组包含精简后的子 JSON。
     * 支持二次拆分：当单个 Tag 下的内容超过上下文限制时，拆分为多个子组。
     */
    private List<TagGroup> splitByTags(String openApiJson) {
        if (openApiJson == null || openApiJson.isBlank()) {
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(openApiJson);
            JsonNode pathsNode = root.get("paths");
            if (pathsNode == null || !pathsNode.isObject()) {
                return Collections.emptyList();
            }

            Map<String, List<String>> tagToPaths = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> pathsIt = pathsNode.fields();
            while (pathsIt.hasNext()) {
                Map.Entry<String, JsonNode> pathEntry = pathsIt.next();
                String path = pathEntry.getKey();
                JsonNode pathNode = pathEntry.getValue();

                Iterator<Map.Entry<String, JsonNode>> methodsIt = pathNode.fields();
                while (methodsIt.hasNext()) {
                    Map.Entry<String, JsonNode> methodEntry = methodsIt.next();
                    JsonNode methodNode = methodEntry.getValue();
                    if (methodNode.has("tags") && methodNode.get("tags").isArray()) {
                        for (JsonNode tagNode : methodNode.get("tags")) {
                            String tag = tagNode.asText();
                            tagToPaths.computeIfAbsent(tag, k -> new ArrayList<>()).add(path);
                        }
                    } else {
                        tagToPaths.computeIfAbsent("未分组", k -> new ArrayList<>()).add(path);
                    }
                }
            }

            int maxContextTokens = getOrProbeMaxContextTokens();
            List<TagGroup> result = new ArrayList<>();

            for (Map.Entry<String, List<String>> entry : tagToPaths.entrySet()) {
                String tagName = entry.getKey();
                List<String> tagPaths = entry.getValue();

                List<List<String>> subGroups = new ArrayList<>();
                List<String> currentGroup = new ArrayList<>();
                int currentTokens = 0;

                for (String path : tagPaths) {
                    int pathTokens = estimateTokens(path) + 200;
                    if (currentGroup.size() > 0 && currentTokens + pathTokens > maxContextTokens / 2) {
                        subGroups.add(currentGroup);
                        currentGroup = new ArrayList<>();
                        currentTokens = 0;
                    }
                    currentGroup.add(path);
                    currentTokens += pathTokens;
                }
                if (!currentGroup.isEmpty()) {
                    subGroups.add(currentGroup);
                }

                for (int i = 0; i < subGroups.size(); i++) {
                    ObjectNode subRoot = objectMapper.createObjectNode();
                    if (root.has("openapi")) subRoot.set("openapi", root.get("openapi"));
                    if (root.has("info")) subRoot.set("info", root.get("info"));

                    ObjectNode subPaths = objectMapper.createObjectNode();
                    for (String path : subGroups.get(i)) {
                        if (pathsNode.has(path)) {
                            subPaths.set(path, pathsNode.get(path));
                        }
                    }
                    subRoot.set("paths", subPaths);

                    if (root.has("components")) {
                        subRoot.set("components", root.get("components"));
                    }

                    String content = simplifyOpenApiJson(objectMapper.writeValueAsString(subRoot));
                    TagGroup group = new TagGroup();
                    group.setTagName(tagName);
                    group.setSubGroupIndex(i + 1);
                    group.setTotalSubGroups(subGroups.size());
                    group.setContent(content);
                    result.add(group);
                }
            }

            log.info("[split] 按 Tag 分组完成：{} 个 Tag，共 {} 个子组", tagToPaths.size(), result.size());
            return result;
        } catch (Exception e) {
            log.error("[split] 按 Tag 分组失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 并行执行多个 LLM 调用。每个 TagGroup 独立调用，使用信号量控制并发。
     * 单个分组失败时降级为接口名称列表；全部分组失败时返回空列表。
     */
    private List<String> executeParallelLlmCalls(
            List<TagGroup> tagGroups,
            String templateName,
            String skillDocType,
            String ragDocType) {

        if (tagGroups == null || tagGroups.isEmpty()) {
            return Collections.emptyList();
        }

        LlmProvider llm = llmProviderFactory.getProvider();
        int maxConcurrency = Math.min(llm.getMaxConcurrency(), properties.getLlm().getMaxConcurrency());
        Semaphore semaphore = new Semaphore(maxConcurrency);
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (TagGroup group : tagGroups) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                semaphore.acquireUninterruptibly();
                try {
                    String queryText = buildQueryFromOpenApi(group.getContent(), 800);
                    RagResult rag = ragDocType != null ? retrieveRagContext(ragDocType, queryText) : RagResult.empty();
                    String skillContext = skillDocType != null ? safeLoadSkill(skillDocType) : "";

                    PromptTemplateManager.Template template = buildPrompt(templateName, group.getContent(), rag.getContext(), rag.getSummary(), skillContext);
                    if (template == null) {
                        log.warn("[parallel] 无法构建 Prompt，templateName={}", templateName);
                        return buildFallbackContent(group);
                    }

                    try {
                        String result = llm.generate(template.getSystemPrompt(), template.getUserPrompt());
                        log.debug("[parallel] tag={}, subGroup={}/{}, resultLength={}",
                                group.getTagName(), group.getSubGroupIndex(), group.getTotalSubGroups(),
                                result != null ? result.length() : 0);
                        return result != null ? result : buildFallbackContent(group);
                    } catch (Exception e) {
                        log.warn("[parallel] LLM 调用失败，tag={}: {}", group.getTagName(), e.getMessage());
                        return buildFallbackContent(group);
                    }
                } finally {
                    semaphore.release();
                }
            });
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(properties.getLlm().getTimeoutSeconds() * 2L, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[parallel] 并行调用超时: {}", e.getMessage());
        }

        List<String> results = new ArrayList<>();
        boolean allFailed = true;
        for (int i = 0; i < futures.size(); i++) {
            try {
                String result = futures.get(i).getNow(null);
                if (result != null && !result.isBlank()) {
                    results.add(result);
                    allFailed = false;
                } else {
                    results.add(buildFallbackContent(tagGroups.get(i)));
                }
            } catch (Exception e) {
                results.add(buildFallbackContent(tagGroups.get(i)));
            }
        }

        if (allFailed) {
            log.warn("[parallel] 全部分组调用失败");
        }

        return results;
    }

    /**
     * 根据模板名称构建 Prompt。
     */
    private PromptTemplateManager.Template buildPrompt(String templateName, String openApiJson,
                                                       String ragContext, String ragSummary, String skillContext) {
        return switch (templateName) {
            case "generate-guide" -> promptTemplateManager.buildGenerateGuidePrompt(openApiJson, ragContext, ragSummary, skillContext);
            case "generate-spec" -> promptTemplateManager.buildGenerateSpecPrompt(openApiJson, ragContext, ragSummary, skillContext);
            case "generate-requirement" -> promptTemplateManager.buildRequirementDocPrompt(openApiJson, ragContext, ragSummary, skillContext);
            case "generate-delivery" -> promptTemplateManager.buildDeliveryDocPrompt(openApiJson, ragContext, ragSummary, skillContext);
            case "generate-testcases" -> promptTemplateManager.buildTestCaseDocPrompt(openApiJson, ragContext, ragSummary, skillContext);
            case "simplify-descriptions" -> promptTemplateManager.buildSimplifyDescriptionsPrompt(openApiJson, ragContext, ragSummary, skillContext);
            default -> null;
        };
    }

    /**
     * 根据模板名称构建 Prompt（支持 generate-overview 额外参数）。
     */
    private PromptTemplateManager.Template buildPrompt(String templateName, String openApiJson,
                                                       String ragContext, String ragSummary, String skillContext,
                                                       String projectTitle, String moduleCount) {
        if ("generate-overview".equals(templateName)) {
            return promptTemplateManager.buildGenerateOverviewPrompt(openApiJson, ragContext, ragSummary, skillContext, projectTitle, moduleCount);
        }
        return buildPrompt(templateName, openApiJson, ragContext, ragSummary, skillContext);
    }

    /**
     * 构建降级内容：纯代码拼接的接口名称列表。
     */
    private String buildFallbackContent(TagGroup group) {
        try {
            JsonNode root = objectMapper.readTree(group.getContent());
            JsonNode paths = root.get("paths");
            if (paths == null || !paths.isObject()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("## ").append(group.getTagName()).append("\n\n");
            Iterator<Map.Entry<String, JsonNode>> it = paths.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String path = entry.getKey();
                JsonNode pathNode = entry.getValue();
                Iterator<Map.Entry<String, JsonNode>> methodsIt = pathNode.fields();
                while (methodsIt.hasNext()) {
                    Map.Entry<String, JsonNode> methodEntry = methodsIt.next();
                    String method = methodEntry.getKey().toUpperCase();
                    String summary = "";
                    if (methodEntry.getValue().has("summary")) {
                        summary = methodEntry.getValue().get("summary").asText();
                    }
                    sb.append("- ").append(method).append(" ").append(path);
                    if (!summary.isBlank()) {
                        sb.append(" - ").append(summary);
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[fallback] 构建降级内容失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 生成全局框架概述（Refine 润色）。
     */
    private String generateOverview(List<TagGroup> tagGroups, String openApiJson,
                                    String ragContext, String ragSummary, String docTitle,
                                    boolean refineUseDetailed) {
        String projectTitle = extractProjectTitle(openApiJson);
        String moduleCount = String.valueOf(tagGroups.size());
        String overviewSkillContext = safeLoadSkill("doc-overview");

        StringBuilder outlineBuilder = new StringBuilder();
        for (TagGroup group : tagGroups) {
            String summary = extractModuleSummary(group.getSimplifiedContent());
            int interfaceCount = countInterfaces(group.getContent());
            outlineBuilder.append("- ").append(group.getTagName())
                    .append(" | 概述: ").append(summary)
                    .append(" | 接口数: ").append(interfaceCount)
                    .append("\n");
        }
        String outline = outlineBuilder.toString();

        int maxContextTokens = getOrProbeMaxContextTokens();
        int safeLimit = maxContextTokens / 2;
        int outlineTokens = estimateTokens(outline);

        String detailedContent = "";
        boolean useDetailed = refineUseDetailed;

        if (useDetailed) {
            StringBuilder detailedBuilder = new StringBuilder();
            for (TagGroup group : tagGroups) {
                if (group.getGeneratedContent() != null && !group.getGeneratedContent().isBlank()) {
                    detailedBuilder.append("## ").append(group.getTagName()).append("\n\n");
                    detailedBuilder.append(group.getGeneratedContent()).append("\n\n");
                }
            }
            detailedContent = detailedBuilder.toString();
            int totalTokens = outlineTokens + estimateTokens(detailedContent) + 2000;
            if (totalTokens > safeLimit) {
                log.warn("[refine] 详细描述 Token 超限（{} > {}），降级为精简描述", totalTokens, safeLimit);
                useDetailed = false;
            }
        }

        if (!useDetailed) {
            int totalTokens = outlineTokens + 1000;
            if (totalTokens > safeLimit) {
                log.warn("[refine] 精简大纲 Token 超限（{} > {}），跳过润色", totalTokens, safeLimit);
                return null;
            }
        }

        String overviewRagContext = ragContext;
        if (useDetailed && !detailedContent.isEmpty()) {
            overviewRagContext += "\n\n[详细模块内容参考]\n" + detailedContent;
        }

        try {
            PromptTemplateManager.Template template = buildPrompt("generate-overview", outline,
                    overviewRagContext, ragSummary, overviewSkillContext, docTitle, moduleCount);
            LlmProvider llm = llmProviderFactory.getProvider();
            String result = llm.generate(template.getSystemPrompt(), template.getUserPrompt());
            log.info("[refine] 全局框架生成完成");
            return stripMarkdownCodeFence(result);
        } catch (Exception e) {
            log.warn("[refine] 生成全局框架失败，跳过润色: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 OpenAPI JSON 中提取项目标题（info.title）。
     */
    private String extractProjectTitle(String openApiJson) {
        try {
            JsonNode root = objectMapper.readTree(openApiJson);
            JsonNode info = root.get("info");
            if (info != null && info.has("title")) {
                return info.get("title").asText();
            }
        } catch (Exception e) {
            log.warn("提取项目标题失败: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 提取模块摘要（取前 100 字符）。
     */
    private String extractModuleSummary(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) + "..." : trimmed;
    }

    /**
     * 统计接口数量（paths 字段数量）。
     */
    private int countInterfaces(String openApiJson) {
        try {
            JsonNode root = objectMapper.readTree(openApiJson);
            JsonNode paths = root.get("paths");
            if (paths != null && paths.isObject()) {
                return paths.size();
            }
        } catch (Exception e) {
            log.warn("统计接口数量失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 通用文档生成方法（分组生成 + 可选 Refine 润色）。
     */
    private ResponseEntity<String> generateDocumentWithGroups(String openApiJson, String templateName,
                                                               String skillDocType, String ragDocType,
                                                               String docTitle, String docTitleEn,
                                                               Function<String, ResponseEntity<String>> legacyMethod) {
        long start = System.currentTimeMillis();
        log.info("[grouped] {}: openApiJson length={}", templateName, openApiJson == null ? 0 : openApiJson.length());

        try {
            String filteredOpenApiJson = filterInternalPaths(openApiJson);
            List<TagGroup> tagGroups = splitByTags(filteredOpenApiJson);

            if (tagGroups == null || tagGroups.isEmpty()) {
                log.info("[grouped] {} 分组为空，降级到一次性调用", templateName);
                return legacyMethod.apply(openApiJson);
            }

            String queryText = buildQueryFromOpenApi(openApiJson, 800);
            RagResult rag = retrieveRagContext(ragDocType, queryText);
            String skillContext = safeLoadSkill(skillDocType);

            log.info("[grouped] {} 阶段一：精简描述（{} 个分组）", templateName, tagGroups.size());
            List<String> simplifiedTexts = executeParallelLlmCalls(tagGroups, "simplify-descriptions",
                    "simplify-descriptions", ragDocType);
            for (int i = 0; i < tagGroups.size() && i < simplifiedTexts.size(); i++) {
                if (simplifiedTexts.get(i) != null) {
                    tagGroups.get(i).setSimplifiedContent(simplifiedTexts.get(i));
                }
            }

            int successCount = (int) tagGroups.stream()
                    .filter(g -> g.getSimplifiedContent() != null && !g.getSimplifiedContent().isBlank())
                    .count();
            log.info("[grouped] {} 阶段一完成：成功 {} / {}", templateName, successCount, tagGroups.size());

            if (successCount == 0) {
                log.warn("[grouped] {} 阶段一全部失败，降级到一次性调用", templateName);
                return legacyMethod.apply(openApiJson);
            }

            log.info("[grouped] {} 阶段三：生成文档碎片", templateName);
            List<String> generatedTexts = executeParallelLlmCalls(tagGroups, templateName,
                    skillDocType, ragDocType);
            for (int i = 0; i < tagGroups.size() && i < generatedTexts.size(); i++) {
                if (generatedTexts.get(i) != null) {
                    tagGroups.get(i).setGeneratedContent(generatedTexts.get(i));
                }
            }

            StringBuilder merged = new StringBuilder();
            merged.append("# ").append(docTitle).append("\n\n");
            for (TagGroup group : tagGroups) {
                if (group.getGeneratedContent() != null && !group.getGeneratedContent().isBlank()) {
                    merged.append(group.getGeneratedContent()).append("\n\n");
                } else if (group.getSimplifiedContent() != null && !group.getSimplifiedContent().isBlank()) {
                    merged.append(buildFallbackContent(group)).append("\n\n");
                }
            }

            String mergedDoc = merged.toString().trim();

            Boolean enableRefine = ragConfigService.getConfigOrDefault(ragDocType).getEnableRefine();
            if (enableRefine == null || enableRefine) {
                log.info("[grouped] {} 阶段四：Refine 润色", templateName);
                Boolean refineUseDetailed = ragConfigService.getConfigOrDefault(ragDocType).getRefineUseDetailed();
                String overview = generateOverview(tagGroups, openApiJson,
                        rag.getContext(), rag.getSummary(), docTitle,
                        refineUseDetailed != null && refineUseDetailed);
                if (overview != null && !overview.isBlank()) {
                    mergedDoc = overview + "\n\n" + mergedDoc;
                }
            }

            long cost = System.currentTimeMillis() - start;
            log.info("[grouped] {} 完成: cost={}ms, ragHit={}", templateName, cost, !rag.getContext().isBlank());
            String cleaned = stripMarkdownCodeFence(mergedDoc);
            String finalDoc = appendRagSummary(cleaned, rag.getSummary());
            return ResponseEntity.ok(finalDoc);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[grouped] {} 失败，降级到一次性调用: cost={}ms, {}", templateName, cost, e.getMessage());
            return legacyMethod.apply(openApiJson);
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ErrorResponse {
        private String error;
        private String detail;
    }
}
