# BEAN-REGISTRATION-REPORT

**排查任务：** 任务【排查-11】——排查 AiController Bean 运行时注册失败原因
**排查日期：** 2026-07-07
**排查人：** 代码静态分析 + 运行时测试验证

---

## 一、端点映射状态

### 1.1 测试环境

- **应用：** `swagger-ai-enhancer-demo`，通过 `mvn spring-boot:run` 启动
- **端口：** 18080
- **配置模式：** `swagger-ai-enhancer.ai.mode=embedded`

### 1.2 实际端点响应测试结果

| 端点 | 所属控制器 | 控制器是否有 @RestController | HTTP 响应 |
|-----|-----------|----------------------------|----------|
| `POST /api/ai/generate-spec` | AiController | **无** | **404 Not Found** |
| `POST /api/ai/generate-guide` | AiController | **无** | **404 Not Found** |
| `POST /api/ai/generate-requirement` | AiController | **无** | **404 Not Found** |
| `POST /api/ai/generate-delivery` | AiController | **无** | **404 Not Found** |
| `POST /api/ai/generate-testcases` | AiController | **无** | **404 Not Found** |
| `POST /api/ai/complete-one` | AiController | **无** | **404 Not Found** |
| `POST /api/ai/complete-all` | AiController | **无** | **404 Not Found** |
| `GET  /api/ai/health` | AiController | **无** | **404 Not Found** |
| `GET  /api/ai/model-config` | AiModelConfigController | ✅ 有 | **200 OK** |
| `GET  /api/ai/settings` | AiSettingsController | ✅ 有 | **200 OK** |
| `GET  /api/ai/rag/health` | AiRagController | ✅ 有 | **200 OK** |
| `GET  /v3/api-docs` | springdoc-openapi（第三方） | — | **200 OK** |
| `GET  /swagger-ui.html` | springdoc-openapi（第三方） | — | **200 OK** |

### 1.3 启动日志中的关键证据

启动日志中**没有任何** `Mapped "[/api/ai]` 或 `Mapped "[/api/ai/generate-spec]` 的输出。

但有以下成功注册日志：

```
SwaggerAiAiAutoConfiguration : [embedded] 装配 Milvus 向量存储（集合：swagger_knowledge）
SwaggerAiAiAutoConfiguration : [embedded] 装配 LLM 提供者：ollama
AiModelConfigService         : [ai-model-config] 已从数据库加载：provider=ollama, baseUrl=http://localhost:11434, model=llama3:latest
```

证明：
1. `EmbeddedConfiguration` 条件装配正常（mode=embedded 生效）
2. `VectorStoreProvider`、`LlmProviderFactory` 等依赖 Bean 正常创建
3. `AiController` 的 **Bean 创建成功**（因为其依赖全部正常注入）
4. 但 **AiController 的 @RequestMapping 端点未被 Spring MVC 注册**

---

## 二、Bean 创建状态

### 2.1 依赖链分析

AiController 的构造函数需要以下依赖：

```java
@Bean
@ConditionalOnMissingBean
public AiController aiController(AiEnhancerProperties properties,
                                 ObjectMapper objectMapper,
                                 LlmProviderFactory llmProviderFactory,
                                 PromptTemplateManager promptTemplateManager,
                                 EmbeddingService embeddingService,
                                 VectorStoreProvider vectorStoreProvider) {
    return new AiController(...);
}
```

| 依赖 Bean | 创建状态 | 证据 |
|----------|---------|------|
| `AiEnhancerProperties` | ✅ 正常 | `@EnableConfigurationProperties` 生效 |
| `ObjectMapper` | ✅ 正常 | Spring Boot auto-config 默认提供 |
| `LlmProviderFactory` | ✅ 正常 | 日志显示装配 LLM 提供者 ollama |
| `PromptTemplateManager` | ✅ 正常 | 日志显示 "从 classpath:prompts.yml 读取 7 个模板" |
| `EmbeddingService` | ✅ 正常 | EmbeddedConfiguration 中定义的 @Bean |
| `VectorStoreProvider` | ✅ 正常 | 日志显示"装配 Milvus 向量存储" |

**结论：AiController Bean 本身创建成功（无 BeanCreationException），但 Spring MVC 未将其端点注册。**

---

## 三、根因确认

### 3.1 核心根因：AiController 缺少 `@RestController` 注解

**源代码对比：**

```java
// AiController.java（有问题）
@Slf4j
@RequestMapping("/api/ai")      // ← 只有 @RequestMapping
public class AiController {      // ← 缺少 @RestController / @Controller
    // ... 8 个 @PostMapping / @GetMapping 方法
}
```

```java
// AiRagController.java（正常）
@Slf4j
@RestController                  // ← 有 @RestController
@RequestMapping("/api/ai/rag")
public class AiRagController {
    // ... 端点正常工作
}
```

```java
// AiModelConfigController.java（正常）
@RestController                  // ← 有 @RestController
@RequestMapping(value = "/api/ai/model-config", ...)
public class AiModelConfigController {
    // ... 端点正常工作
}
```

```java
// AiSettingsController.java（正常）
@RestController                  // ← 有 @RestController
@RequestMapping("/api/ai/settings")
public class AiSettingsController {
    // ... 端点正常工作
}
```

```java
// AiClientForwardController.java（正常）
@Slf4j
@RestController                  // ← 有 @RestController
@RequestMapping
@ConditionalOnProperty(...)
public class AiClientForwardController {
    // ... client 模式下正常工作
}
```

### 3.2 Spring MVC 的工作原理

Spring Boot 3.x 的 `RequestMappingHandlerMapping` 通过以下逻辑扫描候选 Bean：

1. **扫描所有候选 Bean**（从 Spring Context）
2. **筛选条件**：类必须有 `@Controller` 或 `@RestController` 或 `@RequestMapping` **元注解**
3. 对于合格类，解析其 `@RequestMapping`/`@PostMapping`/`@GetMapping` 方法，注册为端点

关键：**`@RestController` = `@Controller` + `@ResponseBody`**。`@RestController` 注解本身被 `@Controller` 元注解，因此被 `RequestMappingHandlerMapping` 识别。

而 **`@RequestMapping` 直接写在类上** 在 Spring Boot 2.x/3.x 中**本身不足以**让 Spring MVC 将其识别为控制器类。单独的 `@RequestMapping` 只提供路径信息，**不标记该类为控制器**。

### 3.3 为什么 Bean 创建成功但端点不工作

1. `SwaggerAiAiAutoConfiguration.EmbeddedConfiguration` 中的 `@Bean AiController` 成功创建了一个 **普通 Bean**（不是控制器）
2. 由于类缺少 `@RestController`，`RequestMappingHandlerMapping` 在扫描阶段跳过它
3. 8 个 `@PostMapping`/`@GetMapping` 方法无法被 Spring MVC 识别和注册
4. 因此所有 `/api/ai/*` 端点全部返回 404

### 3.4 对比其他控制器的正常工作证明

| 控制器 | 注解 | 端点响应 |
|-------|------|---------|
| `AiController` | `@RequestMapping("/api/ai")` 只有路径映射注解，无 `@RestController` | 404 |
| `AiModelConfigController` | `@RestController` + `@RequestMapping` | 200 OK |
| `AiSettingsController` | `@RestController` + `@RequestMapping` | 200 OK |
| `AiRagController` | `@RestController` + `@RequestMapping` | 200 OK |
| `AiClientForwardController` | `@RestController` + `@RequestMapping` | （client 模式下工作） |

**对比清晰表明：缺 `@RestController` → 端点 404。**

---

## 四、修复建议

### 4.1 修复方式（最小改动）

在 `swagger-ai-enhancer-ai-starter/src/main/java/com/swagger/ai/enhancer/ai/controller/AiController.java` 中添加 `@RestController` 注解：

```java
@Slf4j
@RestController                   // ← 添加这一行
@RequestMapping("/api/ai")
public class AiController {
    // ... 其余代码保持不变
}
```

### 4.2 验证步骤

修复后，重新执行以下步骤验证：

1. `mvn clean compile -pl swagger-ai-enhancer-ai-starter -am` 编译通过
2. `mvn spring-boot:run -pl swagger-ai-enhancer-demo -am` 启动应用
3. 启动日志中应出现类似：
   ```
   s.w.s.m.m.a.RequestMappingHandlerMapping : Mapped "{[/api/ai/complete-one],methods=[POST]}" onto public org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> com.swagger.ai.enhancer.ai.controller.AiController.completeOne(java.util.Map<java.lang.String, java.lang.Object>)
   s.w.s.m.m.a.RequestMappingHandlerMapping : Mapped "{[/api/ai/generate-spec],methods=[POST]}" onto ...
   ...（共 8 行 Mapped 日志）
   ```
4. `curl -X POST http://localhost:8080/api/ai/generate-spec -H "Content-Type: application/json" -d '{}'` 返回 200（或非 404 的 AI 响应）
5. `curl http://localhost:8080/api/ai/health` 返回 `{"status":"ok",...}`

### 4.3 附带建议（提升代码一致性）

所有 AI 控制器的注解风格统一，建议保持以下一致风格：

```java
// 统一风格：@RestController + @RequestMapping(path = "...")
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiController { ... }
```

---

## 五、总结

| 问题项 | 内容 |
|-------|------|
| **用户报告问题** | POST `/api/ai/generate-spec` 返回 404 |
| **最初猜测** | Bean 注册失败 / 条件注解不匹配 / 依赖缺失 |
| **实际根因** | `AiController` 类缺少 `@RestController` 注解，导致 Spring MVC 的 `RequestMappingHandlerMapping` 不识别它为控制器类，8 个端点全部未注册 |
| **证据** | 1. 有 `@RestController` 的其他 `/api/ai/*` 端点正常返回 200<br> 2. 启动日志无 `/api/ai/` 的 Mapped 记录<br> 3. 5 个控制器类中唯独 `AiController` 缺少 `@RestController` |
| **修复成本** | 1 行代码改动 + 编译测试 |
| **影响范围** | `/api/ai/complete-one`、`/api/ai/complete-all`、`/api/ai/generate-guide`、`/api/ai/generate-spec`、`/api/ai/generate-requirement`、`/api/ai/generate-delivery`、`/api/ai/generate-testcases`、`/api/ai/health` —— 共 8 个端点 |

---

**修复一句话总结：** 在 `AiController.java` 类声明前添加 `@RestController` 注解即可解决所有 404 问题。
