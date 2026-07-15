# 排查-38 报告：内部路径过滤遗漏、生成结果格式偏差和 RAG 指标日志格式错误

## 概览

| 问题编号 | 问题描述 | 严重级别 | 根因 |
|---------|---------|---------|------|
| 问题 1 | 内部路径过滤遗漏 | 高 | `isInternalPath` 前缀列表缺失 `/v3/api-docs-enhanced`；`filterInternalPaths` 只过滤 `paths` 节点，未过滤 `tags` 和 `components.schemas` |
| 问题 2 | 中英双语格式偏差 | 中 | Prompt 模板要求"中英双语"，但 5 个 Skill 文档统一说"语言：中文"，两者冲突导致 LLM 困惑 |
| 问题 3 | RAG 指标日志格式错误 | 低 | SLF4J 不支持 `{:.2f}` 占位符，只支持 `{}` |

---

## 问题 1：内部路径过滤遗漏

### 1.1 isInternalPath 方法现状

**文件**：[AiController.java](swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L322-L335)

```java
private static boolean isInternalPath(String path) {
    if (path == null) return false;
    String[] internalPrefixes = {
            "/api/ai/",
            "/api/ai/rag/",
            "/api/ai/settings/"
    };
    for (String prefix : internalPrefixes) {
        if (path.startsWith(prefix)) {
            return true;
        }
    }
    return false;
}
```

**当前过滤的路径前缀**：

| 前缀 | 覆盖的 Controller | 备注 |
|------|------------------|------|
| `/api/ai/` | AiController、AiRagController、AiSettingsController、AiModelConfigController | **主前缀** |
| `/api/ai/rag/` | AiRagController | **冗余**：已被 `/api/ai/` 覆盖 |
| `/api/ai/settings/` | AiSettingsController | **冗余**：已被 `/api/ai/` 覆盖 |

### 1.2 缺失的路径

**EnhancedOpenApiController**：
- 文件：[EnhancedOpenApiController.java](swagger-ai-enhancer-springdoc-starter/src/main/java/com/swagger/ai/enhancer/springdoc/controller/EnhancedOpenApiController.java#L54)
- 路径：`/v3/api-docs-enhanced`（通过 `${swagger-ai-enhancer.springdoc.enhanced-endpoint:/v3/api-docs-enhanced}` 配置）
- Spring 自动生成的 Tag 名：`enhanced-open-api-controller`（类名转 kebab-case）
- **问题**：`/v3/api-docs-enhanced` 不以 `/api/ai/` 开头，**未被 isInternalPath 过滤**

### 1.3 filterInternalPaths 方法过滤范围不足

**文件**：[AiController.java](swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java#L337-L359)

```java
private String filterInternalPaths(String openApiJson) {
    ...
    if (root.has("paths") && root.get("paths").isObject()) {
        ObjectNode pathsNode = (ObjectNode) root.get("paths");
        // 只过滤 paths 节点下的路径
        ...
    }
    return objectMapper.writeValueAsString(root);
}
```

**问题**：`filterInternalPaths` 方法只过滤 OpenAPI JSON 的 `paths` 节点，未过滤以下节点：

| 节点 | 影响 | 严重级别 |
|------|------|---------|
| `tags` | 内部 Controller 的 Tag（如 `enhanced-open-api-controller`）仍出现在 tags 数组中，影响分组 | 高 |
| `components.schemas` | 内部实体类（如 `AiModelConfigEntity`、`RagSyncMetadataEntity`）仍出现在 schemas 中，LLM 会将其当作业务实体处理 | 中 |

### 1.4 AiModelConfigController 路径确认

**文件**：[AiModelConfigController.java](swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiModelConfigController.java#L35)

- 路径：`/api/ai/model-config`
- **已被 `/api/ai/` 前缀正确覆盖**，不是问题 1 的直接原因

**但 `AiModelConfigEntity` 仍出现在生成文档中的原因**：
- `AiModelConfigEntity` 作为 JPA 实体被 springdoc 扫描，添加到 `components.schemas` 节点
- `filterInternalPaths` 未过滤 `components.schemas`，导致内部实体类泄露

### 1.5 内部路径过滤遗漏清单

| 遗漏项 | 类型 | 根因 |
|--------|------|------|
| `/v3/api-docs-enhanced` | paths 路径 | isInternalPath 前缀列表缺失 |
| `enhanced-open-api-controller` Tag | tags 节点 | filterInternalPaths 未过滤 tags |
| `AiModelConfigEntity` Schema | components.schemas | filterInternalPaths 未过滤 schemas |
| `RagSyncMetadataEntity` Schema | components.schemas | filterInternalPaths 未过滤 schemas（推测存在） |

### 1.6 修复建议（不实施）

1. **在 isInternalPath 前缀列表中添加 `/v3/api-docs-enhanced`**
2. **扩展 filterInternalPaths 方法**，增加对 `tags` 和 `components.schemas` 节点的过滤：
   - 过滤 tags：移除名称为 `enhanced-open-api-controller` 等内部 Tag
   - 过滤 schemas：移除 `AiModelConfigEntity`、`RagSyncMetadataEntity` 等内部实体类的 Schema 定义
3. **清理冗余前缀**：`/api/ai/rag/` 和 `/api/ai/settings/` 已被 `/api/ai/` 覆盖，可移除以保持简洁（可选）

---

## 问题 2：中英双语格式偏差

### 2.1 Prompt 模板中的中英双语要求

**文件**：[prompts.yml](swagger-ai-enhancer-ai-starter/src/main/resources/prompts.yml)

| 模板 | 行号 | 中英双语要求 |
|------|------|------------|
| generate-guide | L170 | 中英双语：每一段落先用中文描述，再追加英文翻译（格式：EN: \<english paragraph\>） |
| generate-spec | L203 | 中英双语：每一段落先用中文描述，再追加英文翻译（格式：EN: \<english paragraph\>） |
| generate-requirement | L235 | 中英双语：每一段落先用中文描述，再追加英文翻译（格式：EN: \<english paragraph\>） |
| generate-delivery | L268 | 中英双语：每一段落先用中文描述，再追加英文翻译（格式：EN: \<english paragraph\>） |
| generate-testcases | L301 | **中文为主**，关键术语保留英文（如 API、HTTP、JSON、GET、POST、404、500） |

### 2.2 Skill 文档中的输出格式要求

**文件**：skills/ 目录下 5 个生成 Skill 文档（修复-70 后的纯文本格式）

| Skill 文档 | 输出格式要求 | 对应 Prompt 模板 |
|-----------|------------|----------------|
| [product-doc.md](swagger-ai-enhancer-ai-starter/src/main/resources/skills/product-doc.md) | 语言：中文 | generate-spec（要求中英双语） |
| [integration-guide.md](swagger-ai-enhancer-ai-starter/src/main/resources/skills/integration-guide.md) | 语言：中文 | generate-guide（要求中英双语） |
| [requirement-doc.md](swagger-ai-enhancer-ai-starter/src/main/resources/skills/requirement-doc.md) | 语言：中文 | generate-requirement（要求中英双语） |
| [delivery-doc.md](swagger-ai-enhancer-ai-starter/src/main/resources/skills/delivery-doc.md) | 语言：中文 | generate-delivery（要求中英双语） |
| [testcase-doc.md](swagger-ai-enhancer-ai-starter/src/main/resources/skills/testcase-doc.md) | 语言：中文 | generate-testcases（要求中文为主） |

### 2.3 冲突分析

**核心冲突**：Prompt 模板要求"中英双语"，但 Skill 文档统一说"语言：中文"。

| 模板 | Prompt 要求 | Skill 要求 | 冲突 |
|------|-----------|-----------|------|
| generate-spec | 中英双语，格式 `EN: <english>` | 语言：中文 | **冲突** |
| generate-guide | 中英双语，格式 `EN: <english>` | 语言：中文 | **冲突** |
| generate-requirement | 中英双语，格式 `EN: <english>` | 语言：中文 | **冲突** |
| generate-delivery | 中英双语，格式 `EN: <english>` | 语言：中文 | **冲突** |
| generate-testcases | 中文为主 | 语言：中文 | 一致（措辞略有差异） |

**冲突影响**：
- LLM 收到两条矛盾的格式指令：Prompt 说"中英双语"，Skill 说"语言：中文"
- LLM 可能产生混淆，导致输出格式偏差：
  - 中文标题下直接是英文正文
  - 中英交替出现
  - 部分段落中英双语，部分段落纯中文

### 2.4 修复-70 的影响

**修复-70 的操作**：将 Skill 文档从 Markdown 格式（`## 输出格式`）改为纯文本格式（`输出格式：`），**未改变"语言：中文"这个内容**。

**结论**：此冲突在修复-70 之前就存在，修复-70 未引入新的冲突，但也未解决已有冲突。

**修复-70 的间接影响**：
- 修复-70 前：Skill 文档使用 Markdown 标题（`## 输出格式`），LLM 可能更容易将其识别为输出模板，权重感知较高
- 修复-70 后：Skill 文档改为纯文本，LLM 对其中"语言：中文"指令的权重感知可能变化，冲突的影响更加明显

### 2.5 修复建议（不实施）

**方案 A（推荐）：统一 Skill 文档的输出格式要求，与 Prompt 模板对齐**
- product-doc.md：将"语言：中文"改为"语言：中英双语，每段中文后追加英文翻译（格式：EN: \<english paragraph\>）"
- integration-guide.md：同上
- requirement-doc.md：同上
- delivery-doc.md：同上
- testcase-doc.md：将"语言：中文"改为"语言：中文为主，关键术语保留英文"

**方案 B：统一 Prompt 模板的格式要求，与 Skill 文档对齐**
- 将 generate-guide/spec/requirement/delivery 的"中英双语"改为"语言：中文"
- 不推荐，因为中英双语是更完整的需求

---

## 问题 3：RAG 指标日志格式错误

### 3.1 问题定位

**文件**：[RagMetricsService.java](swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/service/RagMetricsService.java#L67-L68)

**问题代码**：

```java
log.debug("[RAG] 记录检索指标：docType={}, hitHigh={}, highestScore={:.2f}",
        docType, hitHigh, highestScore);
```

### 3.2 根因分析

**SLF4J 占位符规范**：
- SLF4J 只支持 `{}` 作为占位符
- **不支持** `{:.2f}`、`%s`、`%.2f` 等 Python/Printf 风格的格式化占位符

**实际行为**：
- SLF4J 会将 `{:.2f}` 当作普通字符串 `{:.2f}` 处理
- 第三个参数 `highestScore` 没有对应的 `{}` 占位符，被丢弃
- 日志输出为：`[RAG] 记录检索指标：docType=product-doc, hitHigh=true, highestScore={:.2f}`
- **占位符未被替换为实际数值**

### 3.3 正确写法

```java
// 方案 A：直接使用 {}，由 SLF4J 自动调用 Double.toString()
log.debug("[RAG] 记录检索指标：docType={}, hitHigh={}, highestScore={}",
        docType, hitHigh, highestScore);

// 方案 B：如需保留 2 位小数，在调用前格式化
log.debug("[RAG] 记录检索指标：docType={}, hitHigh={}, highestScore={}",
        docType, hitHigh, String.format("%.2f", highestScore));
```

### 3.4 影响范围

- **影响**：日志可读性降低，`highestScore` 值无法在日志中查看，影响 RAG 检索质量监控
- **不影响**：功能逻辑正确，`stats.record(hitHigh, highestScore, ...)` 中的 `highestScore` 值被正确累加和存储

---

## 总结

| 问题 | 根因 | 修复优先级 | 修复复杂度 |
|------|------|----------|----------|
| 问题 1 | isInternalPath 缺失 `/v3/api-docs-enhanced`；filterInternalPaths 未过滤 tags 和 schemas | 高 | 中 |
| 问题 2 | Prompt 模板要求"中英双语"，Skill 文档说"语言：中文"，两者冲突 | 中 | 低 |
| 问题 3 | SLF4J 不支持 `{:.2f}` 占位符 | 低 | 低 |
