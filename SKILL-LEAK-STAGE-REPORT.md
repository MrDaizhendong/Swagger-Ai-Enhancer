# Skill 包装格式在三个阶段中的影响范围排查报告

**排查日期**：2026-07-14
**排查范围**：PromptTemplateManager.java、AiController.java、prompts.yml

---

## 问题描述

用户点击"生成产品说明书"后，生成的文档中包含了 Skill 文档的原始内容（如"### Skill 文档"、"## 角色定义"、"你是一名资深产品经理"等）。当前默认配置为：启用文档润色 + 使用精简描述润色。

需要确认：
1. 泄露的 Skill 属于哪个阶段？
2. 为什么阶段四 Refine 时会使用阶段三的 Skill？

---

## replacePlaceholders 的 Skill 包装格式分析

### 包装逻辑

在 [PromptTemplateManager.java](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/prompt/PromptTemplateManager.java#L223-L233) 的 `replacePlaceholders` 方法中：

```java
if (skillContext == null || skillContext.isBlank()) {
    result = result.replace(PLACEHOLDER_SKILL, "");
} else {
    String skillBlock = "\n\n### Skill 文档\n" + skillContext.trim() + "\n";
    if (result.contains(PLACEHOLDER_SKILL)) {
        result = result.replace(PLACEHOLDER_SKILL, skillBlock);
    } else {
        result = result + skillBlock;
    }
}
```

### 包装格式

Skill 文档被统一包装为：
```
\n\n### Skill 文档\n<Skill文档内容>\n
```

### 调用范围

**所有阶段共用同一个包装格式**，包括：
- 阶段二（精简）：`simplify-descriptions` 模板
- 阶段三（生成）：`generate-spec`、`generate-guide` 等模板
- 阶段四（Refine）：`generate-overview` 模板

---

## 三个阶段中 Skill 的使用情况一览表

| 阶段 | 模板名称 | Skill 文档 | skillDocType 参数 | 加载位置 |
|------|----------|-----------|------------------|----------|
| **阶段二（精简）** | `simplify-descriptions` | [simplify-descriptions.md](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/resources/skills/simplify-descriptions.md) | `"simplify-descriptions"` | [AiController.java:1347-1348](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L1347-L1348) |
| **阶段三（生成）** | `generate-spec` | [product-doc.md](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/resources/skills/product-doc.md) | `"product-doc"` | [AiController.java:1366-1367](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L1366-L1367) |
| **阶段四（Refine）** | `generate-overview` | **阶段三的 Skill**（如 product-doc.md） | **阶段三的 skillContext** | [AiController.java:1389-1392](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L1389-L1392) |

### 关键发现：阶段四使用了错误的 Skill

在 [AiController.java:1389-1392](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L1389-L1392)：

```java
String overview = generateOverview(tagGroups, openApiJson,
        rag.getContext(), rag.getSummary(), skillContext, docTitle,
        refineUseDetailed != null && refineUseDetailed);
```

这里传入的 `skillContext` 是在 [第1344行](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L1344) 加载的阶段三的 Skill：
```java
String skillContext = safeLoadSkill(skillDocType);  // skillDocType = "product-doc"
```

而阶段四应该使用自己的 Skill 文档 [doc-overview.md](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/resources/skills/doc-overview.md)。

---

## prompts.yml 三个模板的 {skillContext} 占位符

### 1. simplify-descriptions（阶段二）

**位置**：[prompts.yml:99](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/resources/prompts.yml#L99)

```yaml
simplify-descriptions:
  system: |
    你是一名技术文档编辑。你的任务是将以下 API 接口的自然语言描述精简为一句话。
    ...
    {ragContext}
    {ragSummary}
    {skillContext}
```

### 2. generate-spec（阶段三）

**位置**：[prompts.yml:219](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/resources/prompts.yml#L219)

```yaml
generate-spec:
  system: |
    你是一名产品文档撰写专家，擅长将技术内容转化为面向非技术读者的产品说明书。
    ...
    {ragContext}
    {ragSummary}
    {skillContext}
```

### 3. generate-overview（阶段四）

**位置**：[prompts.yml:127](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/resources/prompts.yml#L127)

```yaml
generate-overview:
  system: |
    你是一名资深技术文档主编。你收到了一份由多个模块文档片段拼接而成的 API 项目文档大纲。
    ...
    {ragContext}
    {ragSummary}
    {skillContext}
```

**结论**：三个模板都在 System Prompt 末尾包含 `{skillContext}` 占位符。

---

## 默认配置下泄露的 Skill 归属分析

### 现象

用户看到的泄露内容（"### Skill 文档"、"## 角色定义"、"你是一名资深产品经理"）来自 `product-doc.md`。

### 分析流程

1. **阶段二**：使用 `simplify-descriptions.md` Skill → 内容是精简规则，不包含"角色定义"等内容
2. **阶段三**：使用 `product-doc.md` Skill → 包含"角色定义"、"你是一名资深产品经理"等内容
3. **阶段四**：**错误地使用了阶段三的 `product-doc.md` Skill**（而不是自己的 `doc-overview.md`）

### 为什么阶段四会使用阶段三的 Skill

在 [generateDocumentWithGroups](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L1326-L1409) 方法中：

```
第1344行：加载阶段三的 Skill (skillDocType = "product-doc")
        ↓
第1347-1348行：阶段二使用 "simplify-descriptions" Skill（独立加载）
        ↓
第1366-1367行：阶段三使用阶段三的 Skill（正确）
        ↓
第1389-1392行：阶段四调用 generateOverview，传入阶段三的 skillContext（错误！）
```

**根本原因**：`generateOverview` 方法没有加载自己的 Skill，而是直接使用外部传入的 `skillContext`，这个 `skillContext` 是阶段三的 Skill。

---

## 根因定位

| 层级 | 文件 | 行号 | 问题描述 |
|------|------|------|----------|
| **直接原因** | [AiController.java](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L1389-L1392) | 1389-1392 | 阶段四调用 `generateOverview` 时传入的是阶段三的 `skillContext` |
| **根本原因** | [AiController.java](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L1236-L1278) | 1236-1278 | `generateOverview` 方法本身没有加载阶段四专用的 Skill（`doc-overview.md`），而是依赖外部传入 |
| **包装格式问题** | [PromptTemplateManager.java](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/prompt/PromptTemplateManager.java#L226) | 226 | Skill 被包装为 `### Skill 文档` 格式，易被 LLM 误解为输出内容 |

---

## 修复建议

### 方案一：阶段四使用独立的 Skill（推荐）

修改 `generateOverview` 方法，让它加载自己的 Skill 文档（`doc-overview.md`），而不是使用外部传入的阶段三 Skill。

```java
// 在 generateOverview 方法中，替换外部传入的 skillContext
// 从：
PromptTemplateManager.Template template = buildPrompt("generate-overview", outline,
        overviewRagContext, ragSummary, skillContext, projectTitle, moduleCount);

// 改为：
String overviewSkillContext = safeLoadSkill("doc-overview");
PromptTemplateManager.Template template = buildPrompt("generate-overview", outline,
        overviewRagContext, ragSummary, overviewSkillContext, projectTitle, moduleCount);
```

### 方案二：改进 Skill 包装格式（与排查-34 建议一致）

修改 `PromptTemplateManager.replacePlaceholders` 方法，将 `### Skill 文档` 改为更隐蔽的格式。

```java
// 从：
String skillBlock = "\n\n### Skill 文档\n" + skillContext.trim() + "\n";
// 改为：
String skillBlock = "\n\n" + skillContext.trim();
```

### 方案三：调整阶段四 Skill 的加载时机

在 `generateDocumentWithGroups` 方法中，为阶段四单独加载 Skill：

```java
// 在阶段四之前，添加：
String overviewSkillContext = safeLoadSkill("doc-overview");

// 然后调用：
String overview = generateOverview(tagGroups, openApiJson,
        rag.getContext(), rag.getSummary(), overviewSkillContext, docTitle,
        refineUseDetailed != null && refineUseDetailed);
```

---

## 推荐修复顺序

1. **优先**：方案一（阶段四使用独立 Skill）—— 解决 Skill 混用的根本问题
2. **其次**：方案二（改进包装格式）—— 解决 Skill 内容泄露的直接原因
3. **可选**：方案三（调整加载时机）—— 作为方案一的另一种实现方式

---

## 总结

| 问题 | 答案 |
|------|------|
| **replacePlaceholders 是否三个阶段共用？** | 是，所有阶段都使用同一个方法和包装格式 `### Skill 文档` |
| **阶段二和阶段三的 Prompt 是否完全独立？** | 是，阶段二使用 `simplify-descriptions` Skill，阶段三使用对应 docType 的 Skill |
| **阶段四使用的是哪个阶段的 Skill？** | **错误地使用了阶段三的 Skill**（如 product-doc.md），而不是自己的 doc-overview.md |
| **泄露的 Skill 属于哪个阶段？** | 属于阶段三（product-doc.md），但问题出在阶段四错误地使用了它 |