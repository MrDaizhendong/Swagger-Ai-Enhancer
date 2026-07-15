package com.swagger.ai.enhancer.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 模板管理器。管理 7 个 Prompt 模板：
 *   补全单个描述、补全全部描述、生成集成指南、生成产品说明书、
 *   生成需求文档、生成交付/运维文档、生成测试用例文档。
 *
 * 模板加载优先级：
 *   1. 优先读取 classpath:prompts.yml 中的用户自定义模板
 *   2. 文件不存在或解析失败时，自动回退到本类内置的硬编码默认模板
 *
 * 每个模板支持两个占位符（构建时替换）：
 *   - {openApiJson}：用户传入的上下文或 OpenAPI JSON 字符串
 *   - {ragContext}：知识库检索结果（非空时还会在 systemPrompt 尾部追加标准提示语）
 *
 * 每个模板的 build 方法签名与 AiController 调用保持兼容。
 */
@Slf4j
public class PromptTemplateManager {

    private static final String PROMPT_CONFIG_PATH = "prompts.yml";
    private static final String RAG_INJECT_PREFIX =
            "\n\n参考以下知识库内容（每条标注了相似度分数和处理建议）：\n";
    private static final String RAG_USAGE_RULES =
            "\n知识库使用规则：\n"
                    + "1. 标注 ✅ 的片段具有高相关性，请重点参考\n"
                    + "2. 标注 ⚠️ 的片段相关性一般，如果与当前 API 不匹配可以忽略\n"
                    + "3. 不要编造知识库中不存在的信息\n";
    private static final String PLACEHOLDER_OPENAPI = "{openApiJson}";
    private static final String PLACEHOLDER_RAG = "{ragContext}";
    private static final String PLACEHOLDER_RAG_SUMMARY = "{ragSummary}";
    private static final String PLACEHOLDER_SKILL = "{skillContext}";
    private static final String PLACEHOLDER_PARAM_NAME = "{parameterName}";
    private static final String PLACEHOLDER_PARAM_TYPE = "{parameterType}";
    private static final String PLACEHOLDER_PARAM_IN = "{parameterIn}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 加载后模板容器：key -> {system, user}。若加载失败则为 null，使用默认模板。 */
    private Map<String, TemplateEntry> loadedTemplates;

    public PromptTemplateManager() {
        this.loadedTemplates = loadTemplatesFromClasspath();
        if (this.loadedTemplates != null) {
            log.info("Prompt 模板外部化加载成功：从 classpath:{} 读取 {} 个模板",
                    PROMPT_CONFIG_PATH, this.loadedTemplates.size());
        } else {
            log.info("Prompt 模板外部化未启用：使用内置硬编码默认模板");
        }
    }

    // ========================================================================
    // 内部数据结构：模拟 YAML 结构 templates.<key>.system / .user
    // ========================================================================

    @Data
    public static class TemplateEntry {
        private String system;
        private String user;
    }

    /**
     * 模板返回体：systemPrompt + userPrompt。
     */
    @Data
    @AllArgsConstructor
    public static class Template {
        private String systemPrompt;
        private String userPrompt;
    }

    // ========================================================================
    // YAML 加载逻辑
    // ========================================================================

    /**
     * 尝试从 classpath:prompts.yml 读取模板。
     *
     * @return 解析成功返回 Map（key=模板名, value=TemplateEntry{system,user}），
     *         失败返回 null（调用方将回退到内置默认模板）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, TemplateEntry> loadTemplatesFromClasspath() {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_CONFIG_PATH);
            if (!resource.exists()) {
                log.debug("未找到 classpath:{}，将使用内置默认模板", PROMPT_CONFIG_PATH);
                return null;
            }
            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Object root = yaml.load(is);
                if (!(root instanceof Map)) {
                    log.warn("prompts.yml 根节点不是 Map 结构，回退到默认模板");
                    return null;
                }
                Map<String, Object> rootMap = (Map<String, Object>) root;
                Object templatesObj = rootMap.get("templates");
                if (!(templatesObj instanceof Map)) {
                    log.warn("prompts.yml 缺少 templates 节点，回退到默认模板");
                    return null;
                }
                Map<String, Object> templatesNode = (Map<String, Object>) templatesObj;
                Map<String, TemplateEntry> result = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : templatesNode.entrySet()) {
                    String templateKey = entry.getKey();
                    Object value = entry.getValue();
                    if (!(value instanceof Map)) {
                        log.warn("模板 {} 的值不是 Map，跳过", templateKey);
                        continue;
                    }
                    Map<String, Object> node = (Map<String, Object>) value;
                    TemplateEntry te = new TemplateEntry();
                    te.setSystem(node.get("system") == null ? "" : node.get("system").toString());
                    te.setUser(node.get("user") == null ? "" : node.get("user").toString());
                    result.put(templateKey, te);
                }
                if (result.isEmpty()) {
                    log.warn("prompts.yml 未解析到任何模板，回退到默认模板");
                    return null;
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("解析 prompts.yml 失败：{}，回退到内置默认模板", e.getMessage());
            return null;
        }
    }

    /** 当前是否使用了外部化配置文件加载的模板。 */
    public boolean isExternalized() {
        return this.loadedTemplates != null;
    }

    // ========================================================================
    // 通用构建方法：根据 key 选择外部模板或默认模板，再替换占位符
    // ========================================================================

    /**
     * 通用构建入口（4 参数版本：带 ragSummary + skillContext）。
     *
     * @param key          模板键名（如 "complete-one"）
     * @param openApiJson  替换 {openApiJson} 的值（可为对象或字符串）
     * @param ragContext   替换 {ragContext} 的值（可为 null/空）
     * @param ragSummary   替换 {ragSummary} 的知识库参考情况标注（可为 null/空）
     * @param skillContext 替换 {skillContext} 的 Skill 文档内容（可为 null/空）
     * @param defaultSupplier 当外部模板缺失时的默认模板提供者
     */
    private Template build(String key, Object openApiJson, String ragContext, String ragSummary,
                           String skillContext, DefaultTemplateSupplier defaultSupplier) {
        String serializedContext;
        if (openApiJson instanceof String) {
            serializedContext = (String) openApiJson;
        } else {
            try {
                serializedContext = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(openApiJson);
            } catch (Exception e) {
                serializedContext = String.valueOf(openApiJson);
            }
        }
        if (serializedContext == null || serializedContext.isEmpty()) {
            serializedContext = "{}";
        }

        TemplateEntry external = loadedTemplates == null ? null : loadedTemplates.get(key);
        if (external != null) {
            String system = replacePlaceholders(external.getSystem(), serializedContext, ragContext, ragSummary, skillContext);
            String user = replacePlaceholders(external.getUser(), serializedContext, ragContext, ragSummary, skillContext);
            return new Template(system, user);
        }
        // 回退到内置默认模板，默认模板也需替换 skillContext（若有）
        Template t = defaultSupplier.get(serializedContext, ragContext);
        String system = replaceSkillOnly(t.getSystemPrompt(), skillContext);
        return new Template(system, t.getUserPrompt());
    }

    /**
     * 兼容旧签名的 3 参数构建入口。
     */
    private Template build(String key, Object openApiJson, String ragContext, String ragSummary,
                           DefaultTemplateSupplier defaultSupplier) {
        return build(key, openApiJson, ragContext, ragSummary, "", defaultSupplier);
    }

    /**
     * 替换 {openApiJson}、{ragContext}、{ragSummary}、{skillContext} 占位符（4 参数版本）。
     */
    private String replacePlaceholders(String template, String openApiJson, String ragContext,
                                       String ragSummary, String skillContext) {
        if (template == null) {
            return "";
        }
        String result = template.replace(PLACEHOLDER_OPENAPI, openApiJson == null ? "" : openApiJson);
        if (ragContext == null || ragContext.isBlank()) {
            result = result.replace(PLACEHOLDER_RAG, "");
        } else {
            // 注入知识库内容，并追加分层使用规则
            String ragBlock = RAG_INJECT_PREFIX + ragContext.trim() + RAG_USAGE_RULES;
            if (result.contains(PLACEHOLDER_RAG)) {
                result = result.replace(PLACEHOLDER_RAG, ragBlock);
            } else {
                result = result + ragBlock;
            }
        }
        if (ragSummary == null || ragSummary.isBlank()) {
            result = result.replace(PLACEHOLDER_RAG_SUMMARY, "");
        } else {
            if (result.contains(PLACEHOLDER_RAG_SUMMARY)) {
                result = result.replace(PLACEHOLDER_RAG_SUMMARY, "\n知识库参考情况：\n" + ragSummary);
            }
        }
        if (skillContext == null || skillContext.isBlank()) {
            result = result.replace(PLACEHOLDER_SKILL, "");
        } else {
            String skillBlock = "\n\n<skill>\n" + skillContext.trim() + "\n</skill>\n";
            if (result.contains(PLACEHOLDER_SKILL)) {
                result = result.replace(PLACEHOLDER_SKILL, skillBlock);
            } else {
                // 若模板未预留占位符，作为 System Prompt 的补充加入，保证 Skill 仍然生效
                result = result + skillBlock;
            }
        }
        return result;
    }

    /**
     * 替换 {openApiJson}、{ragContext}、{ragSummary}、{skillContext} 占位符
     * （3 参数版本，保持向后兼容，skillContext 为空）。
     */
    private String replacePlaceholders(String template, String openApiJson, String ragContext, String ragSummary) {
        return replacePlaceholders(template, openApiJson, ragContext, ragSummary, "");
    }

    /** 替换 {openApiJson}、{ragContext}、{ragSummary} 占位符（2 参数版本）。 */
    private String replacePlaceholders(String template, String openApiJson, String ragContext) {
        return replacePlaceholders(template, openApiJson, ragContext, "", "");
    }

    /** 仅替换 Skill 占位符，用于内置默认模板（通常不嵌入 {skillContext} 占位符）。 */
    private String replaceSkillOnly(String template, String skillContext) {
        if (template == null) {
            return "";
        }
        if (skillContext == null || skillContext.isBlank()) {
            return template;
        }
        // 若模板恰好含有显式 {skillContext} 占位符则替换；否则追加到末尾
        if (template.contains(PLACEHOLDER_SKILL)) {
            return template.replace(PLACEHOLDER_SKILL,
                    "\n\n<skill>\n" + skillContext.trim() + "\n</skill>\n");
        }
        return template + "\n\n<skill>\n" + skillContext.trim() + "\n</skill>\n";
    }

    /** 按名称替换三个参数相关的占位符，null 值替换为空字符串。 */
    private String replaceParameterPlaceholders(String template,
                                                String parameterName,
                                                String parameterType,
                                                String parameterIn) {
        if (template == null) {
            return "";
        }
        String result = template;
        result = result.replace(PLACEHOLDER_PARAM_NAME, parameterName == null ? "" : parameterName);
        result = result.replace(PLACEHOLDER_PARAM_TYPE, parameterType == null ? "" : parameterType);
        result = result.replace(PLACEHOLDER_PARAM_IN, parameterIn == null ? "" : parameterIn);
        return result;
    }

    /** 用于提供默认模板的函数式接口。 */
    @FunctionalInterface
    private interface DefaultTemplateSupplier {
        Template get(String openApiJson, String ragContext);
    }

    // ========================================================================
    // 1. 补全单个描述
    // ========================================================================

    public Template buildCompleteOnePrompt(Object context, String ragContext, String ragSummary,
                                           String skillContext) {
        return build("complete-one", context, ragContext, ragSummary, skillContext, this::defaultCompleteOne);
    }

    public Template buildCompleteOnePrompt(Object context, String ragContext, String ragSummary) {
        return buildCompleteOnePrompt(context, ragContext, ragSummary, "");
    }

    public Template buildCompleteOnePrompt(Object context, String ragContext) {
        return buildCompleteOnePrompt(context, ragContext, "", "");
    }

    public Template buildCompleteOnePrompt(Object context) {
        return buildCompleteOnePrompt(context, null, "", "");
    }

    // ========================================================================
    // 1.1 补全单个参数描述
    // ========================================================================

    public Template buildCompleteParameterPrompt(Object openApiJson, String ragContext,
                                                 String ragSummary, String skillContext,
                                                 String parameterName, String parameterType,
                                                 String parameterIn) {
        String serialized;
        if (openApiJson instanceof String) {
            serialized = (String) openApiJson;
        } else {
            try {
                serialized = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(openApiJson);
            } catch (Exception e) {
                serialized = String.valueOf(openApiJson);
            }
        }
        if (serialized == null || serialized.isBlank()) {
            serialized = "{}";
        }

        TemplateEntry external = loadedTemplates == null ? null : loadedTemplates.get("complete-parameter");
        if (external != null) {
            String system = replaceParameterPlaceholders(external.getSystem(), parameterName, parameterType, parameterIn);
            String user = replaceParameterPlaceholders(external.getUser(), parameterName, parameterType, parameterIn);
            system = replacePlaceholders(system, serialized, ragContext, ragSummary, skillContext);
            user = replacePlaceholders(user, serialized, ragContext, ragSummary, skillContext);
            return new Template(system, user);
        }

        // 回退到内置默认模板（针对参数补全）
        Template fallback = defaultCompleteParameter(serialized, ragContext, parameterName, parameterType, parameterIn);
        String system = replaceSkillOnly(fallback.getSystemPrompt(), skillContext);
        return new Template(system, fallback.getUserPrompt());
    }

    public Template buildCompleteParameterPrompt(Object openApiJson, String ragContext, String ragSummary,
                                                 String parameterName, String parameterType, String parameterIn) {
        return buildCompleteParameterPrompt(openApiJson, ragContext, ragSummary, "",
                parameterName, parameterType, parameterIn);
    }

    private Template defaultCompleteParameter(String openApiJson, String ragContext,
                                              String parameterName, String parameterType, String parameterIn) {
        String system = "你是一名资深后端开发工程师，精通 RESTful API 设计。\n"
                + "任务：用户会提供一个 API 接口的上下文和一个参数，请为该参数生成简洁、专业、符合技术文档标准的中文描述。\n"
                + "要求：\n"
                + "1. 结合接口用途（operationSummary）和路径（path）推断参数的业务含义。\n"
                + "2. 如果参数是 ID 类参数（如 id、userId、orderId），说明它标识哪个对象。\n"
                + "3. 如果参数是分页类参数，说明分页规则。\n"
                + "4. 如果参数是筛选类参数，说明可选值和筛选逻辑。\n"
                + "5. 如果无法推断，输出“（待补充业务说明）”。\n"
                + "6. 只输出参数描述文本本身，不要加任何前缀、后缀、解释或客套话。\n"
                + "7. 不要输出 JSON。\n"
                + "8. English follows Chinese: 在中文描述后追加英文版本，格式为 ' | EN: <english>'。";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim() + RAG_USAGE_RULES;
        }
        String user = "接口上下文：\n" + openApiJson
                + "\n\n需要生成描述的参数：\n"
                + "- 参数名：" + (parameterName == null ? "" : parameterName)
                + "\n- 参数类型：" + (parameterType == null ? "" : parameterType)
                + "\n- 参数位置：" + (parameterIn == null ? "" : parameterIn)
                + "\n\n请根据接口上下文和参数信息，生成该参数的中文 + English 描述。"
                + "\n只返回描述文本，不要包含其他任何内容。";
        return new Template(system, user);
    }

    private Template defaultCompleteOne(String openApiJson, String ragContext) {
        String system = "你是一名资深后端开发工程师，精通 RESTful API 设计。\n"
                + "任务：根据用户提供的单个 API 元素上下文，生成简洁、专业、符合技术文档标准的中文描述。\n"
                + "要求：\n"
                + "1. 描述必须准确反映元素含义，基于命名习惯与常见业务场景进行合理推断。\n"
                + "2. 描述长度适中（10 ~ 80 个中文字符）。\n"
                + "3. 只返回纯文本描述，不要加任何标记、标题或解释说明。\n"
                + "4. English follows Chinese: 在中文描述后追加英文版本，格式为 ' | EN: <english>'。";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim();
        }
        String user = "上下文信息：\n" + openApiJson
                + "\n\n请根据上面的上下文，为该元素生成中文 + English 描述。"
                + "\n只返回描述文本，不要包含其他任何内容。";
        return new Template(system, user);
    }

    // ========================================================================
    // 2. 补全全部描述
    // ========================================================================

    public Template buildCompleteAllPrompt(String openApiJson, String ragContext, String ragSummary,
                                           String skillContext) {
        return build("complete-all", openApiJson, ragContext, ragSummary, skillContext, this::defaultCompleteAll);
    }

    public Template buildCompleteAllPrompt(String openApiJson, String ragContext, String ragSummary) {
        return buildCompleteAllPrompt(openApiJson, ragContext, ragSummary, "");
    }

    public Template buildCompleteAllPrompt(String openApiJson, String ragContext) {
        return buildCompleteAllPrompt(openApiJson, ragContext, "", "");
    }

    public Template buildCompleteAllPrompt(String openApiJson) {
        return buildCompleteAllPrompt(openApiJson, null, "", "");
    }

    private Template defaultCompleteAll(String openApiJson, String ragContext) {
        String system = "你是一名资深后端开发工程师，精通 RESTful API 设计与 OpenAPI 规范。\n"
                + "任务：用户会提供一段 OpenAPI JSON，其中大量元素的 description 字段为空。\n"
                + "请识别所有缺失 description 的元素（Operation、Parameter、RequestBody、Response、Schema Property、Tag），\n"
                + "为它们生成简洁、专业的中文描述。\n"
                + "要求：\n"
                + "1. 只返回合法的 JSON 对象，不要包含任何前缀/后缀/Markdown 代码块。\n"
                + "2. JSON 结构：{\"descriptions\":{\"paths|{path}|{method}\":\"描述\", "
                + "\"paths|{path}|{method}|parameters|{name}\":\"描述\", "
                + "\"components|schemas|{schema}|properties|{field}\":\"描述\", "
                + "\"tags|{name}\":\"描述\"}}。\n"
                + "3. 路径中使用 '|' 替代 '.'，以避免键名冲突。\n"
                + "4. 每条描述需中英双语，格式：'中文描述 | EN: English description'。\n"
                + "5. 只处理确实缺失 description 的元素，已有描述的元素不要输出。";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim();
        }
        String user = "以下是需要补全的 OpenAPI JSON：\n\n" + openApiJson
                + "\n\n请返回补全后的 description 映射 JSON。";
        return new Template(system, user);
    }

    // ========================================================================
    // 3. 生成集成指南
    // ========================================================================

    public Template buildGenerateGuidePrompt(String openApiJson, String ragContext, String ragSummary,
                                             String skillContext) {
        return build("generate-guide", openApiJson, ragContext, ragSummary, skillContext, this::defaultGenerateGuide);
    }

    public Template buildGenerateGuidePrompt(String openApiJson, String ragContext, String ragSummary) {
        return buildGenerateGuidePrompt(openApiJson, ragContext, ragSummary, "");
    }

    public Template buildGenerateGuidePrompt(String openApiJson, String ragContext) {
        return buildGenerateGuidePrompt(openApiJson, ragContext, "", "");
    }

    public Template buildGenerateGuidePrompt(String openApiJson) {
        return buildGenerateGuidePrompt(openApiJson, null, "", "");
    }

    private Template defaultGenerateGuide(String openApiJson, String ragContext) {
        String system = "你是一名技术文档工程师，专注为开发者编写高质量的 API 集成指南。\n"
                + "任务：根据用户提供的 OpenAPI JSON，生成一份完整的 API 集成指南（Markdown 格式）。\n"
                + "要求：\n"
                + "1. 中英双语：每一段落先用中文描述，再追加英文翻译（格式：EN: <english paragraph>）。\n"
                + "2. 结构必须包含：\n"
                + "   # API 集成指南 / API Integration Guide\n"
                + "   ## 快速开始 / Quick Start\n"
                + "   ## 认证方式 / Authentication\n"
                + "   ## 核心接口说明 / Core API Reference（每个接口包含：功能、请求示例、响应示例）\n"
                + "   ## 错误处理 / Error Handling\n"
                + "   ## 代码示例 / Code Examples（提供 curl 和 Python requests 两个示例）\n"
                + "3. 请求/响应示例使用 JSON 代码块。\n"
                + "4. 技术术语使用英文（如 HTTP、JSON、REST），其他内容中英双语。\n"
                + "5. 只返回 Markdown 文本，不要包含前缀/后缀/说明性文字。";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim();
        }
        String user = "以下是 API 的 OpenAPI JSON：\n\n" + openApiJson
                + "\n\n请基于上述 JSON 生成完整的集成指南。";
        return new Template(system, user);
    }

    // ========================================================================
    // 4. 生成产品说明书
    // ========================================================================

    public Template buildGenerateSpecPrompt(String openApiJson, String ragContext, String ragSummary,
                                            String skillContext) {
        return build("generate-spec", openApiJson, ragContext, ragSummary, skillContext, this::defaultGenerateSpec);
    }

    public Template buildGenerateSpecPrompt(String openApiJson, String ragContext, String ragSummary) {
        return buildGenerateSpecPrompt(openApiJson, ragContext, ragSummary, "");
    }

    public Template buildGenerateSpecPrompt(String openApiJson, String ragContext) {
        return buildGenerateSpecPrompt(openApiJson, ragContext, "", "");
    }

    public Template buildGenerateSpecPrompt(String openApiJson) {
        return buildGenerateSpecPrompt(openApiJson, null, "", "");
    }

    private Template defaultGenerateSpec(String openApiJson, String ragContext) {
        String system = "你是一名产品文档撰写专家，擅长将技术内容转化为面向非技术读者的产品说明书。\n"
                + "任务：根据用户提供的 OpenAPI JSON，生成一份产品说明书（Markdown 格式）。\n"
                + "要求：\n"
                + "1. 中英双语：每一段落先用中文描述，再追加英文翻译（格式：EN: <english paragraph>）。\n"
                + "2. 结构必须包含：\n"
                + "   # 产品说明书 / Product Specification\n"
                + "   ## 功能概述 / Feature Overview\n"
                + "   ## 接口清单（业务描述） / Interface List (Business Description)\n"
                + "   ## 使用场景示例 / Usage Scenarios\n"
                + "   ## 注意事项 / Notes\n"
                + "3. 禁止出现 JSON 结构、HTTP 状态码或其他技术细节，用业务语言描述。\n"
                + "4. 语言通俗易懂，适合产品经理、运营人员或客户阅读。\n"
                + "5. 只返回 Markdown 文本，不要包含前缀/后缀/说明性文字。";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim();
        }
        String user = "以下是 API 的 OpenAPI JSON：\n\n" + openApiJson
                + "\n\n请基于上述 JSON 生成产品说明书。";
        return new Template(system, user);
    }

    // ========================================================================
    // 5. 生成需求文档
    // ========================================================================

    public Template buildRequirementDocPrompt(String openApiJson, String ragContext, String ragSummary,
                                            String skillContext) {
        return build("generate-requirement", openApiJson, ragContext, ragSummary, skillContext, this::defaultGenerateRequirement);
    }

    public Template buildRequirementDocPrompt(String openApiJson, String ragContext, String ragSummary) {
        return buildRequirementDocPrompt(openApiJson, ragContext, ragSummary, "");
    }

    public Template buildRequirementDocPrompt(String openApiJson, String ragContext) {
        return buildRequirementDocPrompt(openApiJson, ragContext, "", "");
    }

    public Template buildRequirementDocPrompt(String openApiJson) {
        return buildRequirementDocPrompt(openApiJson, null, "", "");
    }

    private Template defaultGenerateRequirement(String openApiJson, String ragContext) {
        String system = "你是一名资深系统架构师，擅长撰写 API 需求规格文档（面向软件工程师 SE、架构师、技术经理）。\n"
                + "任务：根据用户提供的 OpenAPI JSON，生成一份结构化的需求文档（Markdown 格式）。\n"
                + "要求：\n"
                + "1. 中英双语：每一段落先用中文描述，再追加英文翻译（格式：EN: <english paragraph>）。\n"
                + "2. 结构必须包含：\n"
                + "   # API 需求规格文档 / API Requirement Specification\n"
                + "   ## 功能概述 / Feature Overview（2-3 句话说明该 API 提供的核心业务能力）\n"
                + "   ## 接口清单 / Interface List（按模块分组，列出每个接口：路径、方法、用途说明——用业务语言描述，不写技术细节）\n"
                + "   ## 输入输出约束 / Input & Output Constraints（每个接口的必填参数、可选参数、参数类型、取值范围）\n"
                + "   ## 业务规则 / Business Rules（跨接口的业务约束，例如 \"创建用户后才能创建订单\"）\n"
                + "   ## 异常处理 / Exception Handling（各接口可能的错误场景与业务含义）\n"
                + "   ## 变更历史 / Change History（预留章节，内容写 \"待补充\"）\n"
                + "3. 只返回 Markdown 文本，不要包含前缀/后缀/说明性文字。\n"
                + "4. 技术术语保留英文（如 API、HTTP、JSON、REST），其他内容中英双语。";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim();
        }
        String user = "以下是 API 的 OpenAPI JSON：\n\n" + openApiJson
                + "\n\n请基于上述 JSON 生成需求规格文档。";
        return new Template(system, user);
    }

    // ========================================================================
    // 6. 生成交付/运维文档
    // ========================================================================

    public Template buildDeliveryDocPrompt(String openApiJson, String ragContext, String ragSummary,
                                            String skillContext) {
        return build("generate-delivery", openApiJson, ragContext, ragSummary, skillContext, this::defaultGenerateDelivery);
    }

    public Template buildDeliveryDocPrompt(String openApiJson, String ragContext, String ragSummary) {
        return buildDeliveryDocPrompt(openApiJson, ragContext, ragSummary, "");
    }

    public Template buildDeliveryDocPrompt(String openApiJson, String ragContext) {
        return buildDeliveryDocPrompt(openApiJson, ragContext, "", "");
    }

    public Template buildDeliveryDocPrompt(String openApiJson) {
        return buildDeliveryDocPrompt(openApiJson, null, "", "");
    }

    private Template defaultGenerateDelivery(String openApiJson, String ragContext) {
        String system = "你是一名资深技术交付经理，擅长撰写软件交付文档与运维手册（面向客户技术团队、运维工程师）。\n"
                + "任务：根据用户提供的 OpenAPI JSON，生成一份交付与运维说明文档（Markdown 格式）。\n"
                + "要求：\n"
                + "1. 中英双语：每一段落先用中文描述，再追加英文翻译（格式：EN: <english paragraph>）。\n"
                + "2. 结构必须包含：\n"
                + "   # 软件交付与运维手册 / Software Delivery & Operations Manual\n"
                + "   ## 项目概述 / Project Overview（项目名称、版本、交付范围）\n"
                + "   ## 部署环境要求 / Deployment Requirements（操作系统、JDK 版本、中间件、端口规划）\n"
                + "   ## 配置说明 / Configuration Guide（所有配置项列表及说明，标注必填项）\n"
                + "   ## API 端点清单 / API Endpoint Inventory（每个接口：路径、方法、认证方式、限流说明）\n"
                + "   ## 运维注意事项 / Operations Notes（日志路径、监控指标、备份策略、常见故障处理）\n"
                + "   ## 联系方式 / Contact（预留章节，内容写 \"待补充\"）\n"
                + "3. 只返回 Markdown 文本，不要包含前缀/后缀/说明性文字。\n"
                + "4. 技术术语保留英文。";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim();
        }
        String user = "以下是 API 的 OpenAPI JSON：\n\n" + openApiJson
                + "\n\n请基于上述 JSON 生成交付与运维说明文档。";
        return new Template(system, user);
    }

    // ========================================================================
    // 7. 生成测试用例文档
    // ========================================================================

    public Template buildTestCaseDocPrompt(String openApiJson, String ragContext, String ragSummary,
                                            String skillContext) {
        return build("generate-testcases", openApiJson, ragContext, ragSummary, skillContext, this::defaultGenerateTestCases);
    }

    public Template buildTestCaseDocPrompt(String openApiJson, String ragContext, String ragSummary) {
        return buildTestCaseDocPrompt(openApiJson, ragContext, ragSummary, "");
    }

    public Template buildTestCaseDocPrompt(String openApiJson, String ragContext) {
        return buildTestCaseDocPrompt(openApiJson, ragContext, "", "");
    }

    public Template buildTestCaseDocPrompt(String openApiJson) {
        return buildTestCaseDocPrompt(openApiJson, null, "", "");
    }

    private Template defaultGenerateTestCases(String openApiJson, String ragContext) {
        String system = "你是一名资深测试架构师，擅长设计 API 测试用例。\n"
                + "任务：根据用户提供的 OpenAPI JSON，生成一份结构化的测试用例文档（Markdown 格式）。\n"
                + "要求：\n"
                + "1. 中文为主，关键术语保留英文（如 API、HTTP、JSON、GET、POST、404、500）。\n"
                + "2. 结构必须包含：\n"
                + "   # API 测试用例文档 / API Test Case Documentation\n"
                + "   ## 测试范围 / Test Scope（覆盖的接口和场景说明）\n"
                + "   ## 正向测试用例 / Positive Test Cases（每个接口至少 1 条，格式：表格，列为 \"用例编号/接口/输入/预期输出\"）\n"
                + "   ## 异常测试用例 / Negative Test Cases（参数缺失、类型错误、越界值、未认证、无权限等）\n"
                + "   ## 边界测试用例 / Boundary Test Cases（空值、特殊字符、超长字符串、并发请求）\n"
                + "   ## 性能测试建议 / Performance Test Recommendations（需压测的接口与并发量建议）\n"
                + "3. 所有测试用例以表格形式呈现，列标题使用中英双语：用例编号/No、接口/Endpoint、输入/Input、预期输出/Expected Output。\n"
                + "4. 只返回 Markdown 文本，不要包含前缀/后缀/说明性文字。";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim();
        }
        String user = "以下是 API 的 OpenAPI JSON：\n\n" + openApiJson
                + "\n\n请基于上述 JSON 生成测试用例文档。";
        return new Template(system, user);
    }

    // ========================================================================
    // 9. 精简接口描述（阶段二共用）
    // ========================================================================

    public Template buildSimplifyDescriptionsPrompt(String openApiJson, String ragContext, String ragSummary,
                                                    String skillContext) {
        return build("simplify-descriptions", openApiJson, ragContext, ragSummary, skillContext, this::defaultSimplifyDescriptions);
    }

    public Template buildSimplifyDescriptionsPrompt(String openApiJson, String ragContext, String ragSummary) {
        return buildSimplifyDescriptionsPrompt(openApiJson, ragContext, ragSummary, "");
    }

    public Template buildSimplifyDescriptionsPrompt(String openApiJson, String ragContext) {
        return buildSimplifyDescriptionsPrompt(openApiJson, ragContext, "", "");
    }

    public Template buildSimplifyDescriptionsPrompt(String openApiJson) {
        return buildSimplifyDescriptionsPrompt(openApiJson, null, "", "");
    }

    private Template defaultSimplifyDescriptions(String openApiJson, String ragContext) {
        String system = "你是一名技术文档编辑。你的任务是将以下 API 接口的自然语言描述精简为一句话。\n"
                + "要求：\n"
                + "1. 将每个接口的自然语言描述精简为一句话，只保留核心业务含义\n"
                + "2. 删除技术细节、冗余修饰和客套话\n"
                + "3. 保留完整的 HTTP 方法和路径不做任何修改\n"
                + "4. English follows Chinese: 在中文描述后追加英文版本，格式为 ' | EN: <english>'\n"
                + "5. 不要输出 JSON 格式，只输出纯文本\n"
                + "6. 不要添加任何前缀、后缀或解释";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim();
        }
        String user = "请精简以下接口的描述：\n" + openApiJson;
        return new Template(system, user);
    }

    // ========================================================================
    // 10. 文档全局润色（阶段四 Refine 共用）
    // ========================================================================

    public Template buildGenerateOverviewPrompt(String openApiJson, String ragContext, String ragSummary,
                                                String skillContext, String projectTitle, String moduleCount) {
        TemplateEntry external = loadedTemplates == null ? null : loadedTemplates.get("generate-overview");
        if (external != null) {
            String system = replacePlaceholders(external.getSystem(), openApiJson, ragContext, ragSummary, skillContext);
            String user = replacePlaceholders(external.getUser(), openApiJson, ragContext, ragSummary, skillContext);
            user = user.replace("{projectTitle}", projectTitle == null ? "" : projectTitle);
            user = user.replace("{moduleCount}", moduleCount == null ? "" : moduleCount);
            return new Template(system, user);
        }
        Template fallback = defaultGenerateOverview(openApiJson, ragContext, projectTitle, moduleCount);
        String system = replaceSkillOnly(fallback.getSystemPrompt(), skillContext);
        return new Template(system, fallback.getUserPrompt());
    }

    public Template buildGenerateOverviewPrompt(String openApiJson, String ragContext, String ragSummary,
                                                String skillContext) {
        return buildGenerateOverviewPrompt(openApiJson, ragContext, ragSummary, skillContext, "", "");
    }

    public Template buildGenerateOverviewPrompt(String openApiJson, String ragContext, String ragSummary) {
        return buildGenerateOverviewPrompt(openApiJson, ragContext, ragSummary, "", "", "");
    }

    public Template buildGenerateOverviewPrompt(String openApiJson, String ragContext) {
        return buildGenerateOverviewPrompt(openApiJson, ragContext, "", "", "", "");
    }

    public Template buildGenerateOverviewPrompt(String openApiJson) {
        return buildGenerateOverviewPrompt(openApiJson, null, "", "", "", "");
    }

    private Template defaultGenerateOverview(String openApiJson, String ragContext,
                                             String projectTitle, String moduleCount) {
        String system = "你是一名资深技术文档主编。你收到了一份由多个模块文档片段拼接而成的 API 项目文档大纲。\n"
                + "你的任务是生成一份全局文档框架。\n"
                + "\n"
                + "要求：\n"
                + "1. **全局功能概述**：2-3 句话，涵盖所有模块的核心业务能力。\n"
                + "   必须基于实际模块功能撰写，不使用\"AI 解决方案\"等泛泛而谈的描述。\n"
                + "\n"
                + "2. **模块关联说明**：基于大纲中的\"依赖关系\"，说明各模块之间的业务关联。\n"
                + "   例如：\"订单管理依赖用户管理（需验证用户身份）和商品管理（需确认商品库存）\"\n"
                + "\n"
                + "3. **典型使用场景**：2-3 个端到端的业务场景示例。\n"
                + "   例如：\"用户注册 → 浏览商品 → 创建订单 → 支付 → 查询物流\"\n"
                + "\n"
                + "4. 不要列出每个接口的详细描述——那部分内容会由代码自动拼接。\n"
                + "5. 使用中文撰写，关键术语保留英文。\n"
                + "6. 输出格式为 Markdown，使用 ## 和 ### 作为标题层级。";
        if (ragContext != null && !ragContext.isBlank()) {
            system = system + RAG_INJECT_PREFIX + ragContext.trim();
        }
        String user = "项目名称：" + (projectTitle == null ? "" : projectTitle) + "\n"
                + "模块数量：" + (moduleCount == null ? "" : moduleCount) + "\n"
                + "\n"
                + "模块大纲：\n"
                + openApiJson
                + "\n\n"
                + "请生成全局文档框架。";
        return new Template(system, user);
    }
}
