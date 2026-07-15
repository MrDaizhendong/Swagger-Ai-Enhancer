# Skill 文档内容泄露到生成结果的问题排查报告

**排查日期**：2026-07-14
**排查范围**：AiController.buildPrompt、PromptTemplateManager.replacePlaceholders、prompts.yml

---

## 问题描述

用户点击"生成产品说明书"等文档生成按钮后，LLM 返回的内容中包含了 Skill 文档的原始元信息，如：
- `### Skill 文档`
- `## 角色定义`
- `你是一名资深产品经理`
- `## 目标读者`
- `## 文档结构`

这些内容本应作为 System Prompt 的一部分指导 LLM 的行为，不应出现在最终生成的文档中。

---

## Prompt 结构分析

### 1. prompts.yml 模板结构

以 `generate-spec`（产品说明书）模板为例：

| 部分 | 内容 |
|------|------|
| **System Prompt** | 角色定义（产品文档撰写专家）+ 任务要求 + 输出格式要求 + `{ragContext}` + `{ragSummary}` + `{skillContext}` |
| **User Prompt** | `{openApiJson}` + "请基于上述 JSON 生成产品说明书。" |

所有 5 个生成模板（generate-spec、generate-guide、generate-requirement、generate-delivery、generate-testcases）的 `{skillContext}` 占位符都位于 **System Prompt 的末尾**，紧接在 `{ragSummary}` 之后。

### 2. replacePlaceholders 替换逻辑

在 [PromptTemplateManager.java](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/prompt/PromptTemplateManager.java#L223-L233) 的 `replacePlaceholders` 方法中，当 `skillContext` 非空时：

```java
String skillBlock = "\n\n### Skill 文档\n" + skillContext.trim() + "\n";
if (result.contains(PLACEHOLDER_SKILL)) {
    result = result.replace(PLACEHOLDER_SKILL, skillBlock);
} else {
    result = result + skillBlock;
}
```

**关键问题**：Skill 文档被包装成带有明显 Markdown 标题的块：
```
\n\n### Skill 文档\n<Skill文档内容>\n
```

### 3. 实际生成的 System Prompt 结构

以 generate-spec + product-doc Skill 为例，最终的 System Prompt 结构如下：

```
你是一名产品文档撰写专家，擅长将技术内容转化为面向非技术读者的产品说明书。
任务：根据用户提供的 OpenAPI JSON，生成一份产品说明书（Markdown 格式）。
要求：
...（省略中间要求）...

### Skill 文档
# 产品说明书生成 Skill

## 角色定义
你是一名资深产品经理，擅长撰写面向非技术人员的 API 产品功能说明书。

## 目标读者
产品经理、业务人员、客户、非技术决策者。

## 文档结构
1. **功能概述**：...
2. **接口清单（业务描述）**：...
...
```

---

## 根因定位

### 根本原因：Skill 文档被包装成了 LLM 容易误解的格式

**问题 1：明显的 Markdown 标题标记**

`### Skill 文档` 这个标题标记让 LLM 误以为这是需要处理或输出的内容。特别是当 Skill 文档内部也包含 `## 角色定义`、`## 文档结构` 等标题时，LLM 更容易将其当作输出要求的一部分。

**问题 2：角色定义冲突**

System Prompt 已经定义了角色（"你是一名产品文档撰写专家"），而 Skill 文档又重新定义了角色（"你是一名资深产品经理"）。这种重复定义可能导致 LLM 混淆，将 Skill 文档的内容当作输出的一部分。

**问题 3：位置问题**

Skill 文档位于 System Prompt 的**末尾**，紧挨着 User Prompt 的开始。这可能让某些 LLM 误以为 Skill 文档也是用户输入的一部分，需要总结或处理。

### 涉及代码位置

| 文件 | 行号 | 问题 |
|------|------|------|
| [PromptTemplateManager.java](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/prompt/PromptTemplateManager.java#L226) | 第226行 | `skillBlock` 使用 `### Skill 文档` 作为前缀 |
| [prompts.yml](file:///e:/Projects/VibeCodingProjects/TraeProjects/Swagger%20Ai%20Enhancer/swagger-ai-enhancer-ai-starter/src/main/resources/prompts.yml#L219) | 第219行 | `{skillContext}` 位于 System Prompt 末尾 |

---

## 修复建议

### 方案一：改进 Skill 文档的包装格式（推荐）

将 Skill 文档的包装从明显的 Markdown 标题改为更隐蔽的格式，让 LLM 明确知道这是指导信息而非输出内容。

```java
// 修改 PromptTemplateManager.java 第226行
// 从：
String skillBlock = "\n\n### Skill 文档\n" + skillContext.trim() + "\n";
// 改为：
String skillBlock = "\n\n【内部指导】\n" + skillContext.trim() + "\n";
```

或者更简洁：
```java
String skillBlock = "\n\n" + skillContext.trim();
```

### 方案二：调整 Skill 文档在 System Prompt 中的位置

将 `{skillContext}` 移到 System Prompt 的开头（角色定义之后），而不是末尾。这样可以让 LLM 先了解指导原则，再接收具体任务要求。

修改 prompts.yml 中所有模板的 `{skillContext}` 位置，从末尾移到角色定义之后：

```yaml
generate-spec:
  system: |
    你是一名产品文档撰写专家，擅长将技术内容转化为面向非技术人员的产品说明书。
    {skillContext}
    任务：根据用户提供的 OpenAPI JSON，生成一份产品说明书（Markdown 格式）。
    ...
```

### 方案三：在 Skill 文档末尾添加明确的分隔符

在 Skill 文档末尾添加明确的分隔符，告诉 LLM Skill 文档结束，接下来是正式任务。

```java
String skillBlock = "\n\n" + skillContext.trim() + "\n\n【指导结束，开始执行任务】\n";
```

### 方案四：简化 Skill 文档内容

移除 Skill 文档中的 Markdown 标题格式，使其更像纯文本指导而非结构化文档。

---

## 推荐修复顺序

1. **优先**：方案一（改进包装格式）——改动最小，风险最低
2. **其次**：方案二（调整位置）——需要修改所有模板，但效果更稳定
3. **可选**：方案三（添加分隔符）——作为补充措施

---

## 验证方法

修复后，通过以下方式验证：

1. 调用 `/api/ai/generate-spec` 接口生成产品说明书
2. 检查返回结果中是否包含 `### Skill 文档`、`## 角色定义` 等 Skill 文档的原始内容
3. 确认生成的文档结构正确（只有产品说明书应有的章节）