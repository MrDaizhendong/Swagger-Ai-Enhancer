# 「补全描述」按钮点击无反应 —— 前端排查报告

> 本文档为只读排查稿，不含任何代码修改建议的实施步骤。

## 1. 现象

- 前端 Swagger UI 中每个接口右侧的 `🤖 补全描述` 按钮，以及 description 列的 `暂无描述，点击 🤖 补全` 占位容器，点击后 UI 无任何变化。
- 后端 `AiController.completeOne` 日志显示接口被调用并成功返回（日志：`complete-one done: cost=...ms, ragHit=...`）。

结论：问题出在前端对 `/api/ai/complete-one` 响应体的解析或 DOM 应用逻辑上。

## 2. 前端调用链

代码位置：`swagger-ai-enhancer-ui-starter/dist/swagger-ai-plugin.js`

### 2.1 入口点

```
2288  renderOperationUI()   // 每个 opblock 注入按钮与占位容器
2295  var btn = createElement("button", { className: "swagger-ai__op-btn" }, ["🤖 补全描述"]);
2299  btn.addEventListener("click", function (evt) { ... handleCompleteOne(opblock, btn); });

2345  function buildPlaceholder(opblock, scope) { ... 点击后触发 btn.click() 或 handleCompleteOne(opblock, null) ... }
```

用户点击的两个入口都路由到 `handleCompleteOne(opblock, btn)`。

### 2.2 handleCompleteOne

```
2468  function handleCompleteOne(opblock, btn) {
2469      if (state.pending) return;                // 全局并发开关；但 handleCompleteOne 自身不设置 pending，
                                                   // 若同时有其他 generate-* 按钮在请求，会直接 return
2475      setButtonLoading(btn, true, "补全中…");   // 会在该按钮上显示 spinner；用户反馈“无任何变化”——
                                                   // 说明要么 btn 未被传入、要么 setButtonLoading 被吞掉
2484      fetch(apiUrl("/api/ai/complete-one"), {
            method: "POST",
            headers: { "Content-Type": "application/json", "Accept": "application/json" },
            body: JSON.stringify(payload)
2488  }).then(function (resp) {
            ... 解析 JSON → return resp.json().catch(function(){return {};}) ...
2499  }).then(function (data) {
            var descriptions = data.descriptions || data.description || data;
            if (descriptions && typeof descriptions === "string") {
                applyDescriptionsToDom(opblock, { operation: descriptions });   // 分支 A
            } else if (descriptions && typeof descriptions === "object") {
                applyDescriptionsToDom(opblock, descriptions);                    // 分支 B
            } else {
                showToast("AI 返回内容为空，未补全任何描述", "info");              // 分支 C
            }
2518  }).catch(function (err) {
            showToast("❌ " + ...)
2519  }).then(function () {
            setButtonLoading(btn, false, "🤖 补全描述");
        });
```

### 2.3 applyDescriptionsToDom

```
2358  function applyDescriptionsToDom(opblock, descriptions) {

         // —— 1) 接口级描述 ——
2365      var opDesc = descriptions.operation
                             || descriptions.summary
                             || descriptions["operation"]
                             || descriptions["description"];
2366      if (opDesc) {
2367          var descEl = $(".opblock-description-wrapper", opblock)
                            || $(".opblock-description", opblock);
2368          if (descEl) {
2369              descEl.innerHTML = "";
2370              var textNode = document.createTextNode(opDesc);
2371              descEl.appendChild(textNode);
2372              descEl.appendChild(createAIBadge());
2373              // 确保不再显示占位容器
2374              var ph = $(".swagger-ai__placeholder", descEl);
2375              if (ph) ph.parentNode.removeChild(ph);
2376          }
        }

         // —— 2) 参数描述 ——
2380      var params = descriptions.parameters || descriptions.params || {};
2382      $$(".parameter__row, tr", opblock).forEach(function (row) {
            var nameCell = $(".parameters-col_name, .parameter__name", row);
            var descCell = $(".parameters-col_description, .parameter__description", row);
            if (!nameCell || !descCell) return;
            ... 用 params[name] 写入 descCell
        });

         // —— 3) 响应描述 ——
2401      var responses = descriptions.responses || {};
2404      $$("tr", opblock).forEach(function (row) {
            var descCell = $(".responses-col_description", row);
            ... 用 responses[codeText] 写入 descCell（且当前内容为空才写入）
        });
    }
```

## 3. 后端响应格式

`swagger-ai-enhancer-ai-starter/.../controller/AiController.java#completeOne`

```java
317  Map<String, Object> response = new LinkedHashMap<>();
318  Map<String, Object> descriptions = new LinkedHashMap<>();
320  Object parsed = safeParseToJsonOrRaw(rawText);
321  if (parsed instanceof Map) {
322      Map<String, Object> parsedMap = (Map<String, Object>) parsed;
324      if (parsedMap.containsKey("descriptions")) {
325          descriptions.putAll(parsedMap);                // ⚠️ descriptions 最终 = { "descriptions": { "operation": "...", ... } }
326      } else {
327          descriptions.putAll(parsedMap);                 // 描述直接写进顶层
328      }
329  } else {
330      String elementType = body == null ? null : (String) body.get("elementType");
331      String key = elementType == null || elementType.isBlank() ? "operation" : elementType;
332      descriptions.put(key, rawText);
333  }
335  response.put("descriptions", descriptions);
336  response.put("ragHit", ...);
337  response.put("ragSummary", ...);
340  return ResponseEntity.ok(response);
```

最终的 JSON 响应：

```json
{
  "descriptions": {
    "descriptions": { "operation": "...", "parameters": { ... }, "responses": { ... } }   // 当 LLM 输出了包裹的 {"descriptions":{...}}
  },
  "ragHit": true,
  "ragSummary": "..."
}
```

或：

```json
{
  "descriptions": {
    "operation": "...（整段纯文本）",   // 当 LLM 输出纯文本，或解析失败时
  },
  "ragHit": true,
  "ragSummary": "..."
}
```

## 4. 根因定位

### 根因 A（主要）：后端 JSON 响应与前端解析逻辑不匹配

- `PromptTemplateManager.defaultCompleteOne()` 要求 LLM 只返回纯文本描述，但它没有要求 LLM 以 JSON 形式返回 `{operation, parameters, responses}` 这样的结构；
- 当 LLM 返回了纯文本（最常见情况），后端会走 `safeParseToJsonOrRaw` 的"解析失败/非 JSON"分支，最终把整段纯文本作为 `descriptions.operation` 的值；
- 此时 `descriptions.parameters`、`descriptions.responses` 必然为空，参数行与响应行永远不会被补全；
- 更严重的是，当 LLM 偶尔输出 `{"descriptions":{...}}` 时，后端把 `parsedMap` 的内容（含 `descriptions` 键）整个 `putAll` 到 `descriptions` Map，于是 `descriptions.descriptions = {operation, parameters, responses}` 被嵌套了一层；
- 前端 `applyDescriptionsToDom` 的行2365/2380/2401分别取顶层键 `operation / parameters / responses`，**不会再向下钻探** `descriptions.descriptions.operation`，导致：
  - `opDesc` 为 undefined → `if (opDesc)` 跳过；
  - `params` 为 `{}` → 循环体直接返回；
  - `responses` 为 `{}` → 循环体直接返回。
- 前端看起来就像“无任何变化”。

### 根因 B：按钮 loading 状态也无法呈现，进一步误导用户

- `handleCompleteOne(...)` 调用 `setButtonLoading(btn, true, "补全中…")`；
- `setButtonLoading` 第120行 `if (!btn) return;`——当用户点的是「暂无描述，点击 🤖 补全」占位容器时，`buildPlaceholder` 第2352行走的是 `handleCompleteOne(opblock, null)`，`btn` 为 null，直接 return，**按钮不会有 loading 效果**；
- 同时失败也不会显示 toast（因为响应是 200，走 `.then(function(data){...})` 的正常分支）；
- 用户看到的就是：“点击后 UI 纹丝不动”。

### 根因 C：state.pending 会阻塞 complete-one

- `handleCompleteOne` 开头 `if (state.pending) return;`；
- `state.pending` 在 `handleRagSync` / `handleMilvusCheck` 等同步操作中也会被设置；
- `state.pending` 没有在 `handleCompleteOne` 自己的 fetch 链中置位，也**没有异常分支中 reset**；
- 若同步失败后 `state.pending` 未能恢复为 `false`，**则所有后续 complete-one 点击都会被静默吞掉**。

## 5. 当前代码片段定位（精确到行号）

| 位置 | 行号范围 | 函数/元素 | 可疑点 |
|---|---|---|---|
| swagger-ai-plugin.js | 2298-2302 | 接口级 `🤖 补全描述` 按钮 | 正确绑定，点击能触发 handleCompleteOne |
| swagger-ai-plugin.js | 2345-2355 | buildPlaceholder 点击回调 | `btn` 为 null 时进入 `setButtonLoading(btn, ...)` 被第120行吞掉 |
| swagger-ai-plugin.js | 2468-2521 | handleCompleteOne | ① 对 `resp.json()` 抛出的错误有兜底 `{}` 但会走分支 C 打 toast "返回内容为空"（若响应不是 JSON 例如 200+空文本，此逻辑才会被打到）；② `data.descriptions` 是对象时走分支 B，进入 applyDescriptionsToDom 但按顶层键去取值，无法覆盖 LLM 返回的嵌套结构 |
| swagger-ai-plugin.js | 2358-2422 | applyDescriptionsToDom | 只支持顶层的 `operation / parameters / responses` 键；不支持 `descriptions` 再嵌套一层；不支持纯字符串作为 "operation 描述" 以外的 key |
| AiController.java | 317-338 | completeOne 构造响应 | ① `parsedMap.containsKey("descriptions")` 时把整段 `parsedMap` 放进去导致嵌套；② 纯文本 fallback 只写一个 key，其他字段缺失 |
| PromptTemplateManager.java | 290-305 | defaultCompleteOne 模板 | Prompt 的 user/system 未要求 LLM 返回 JSON，只要求返回纯文本；后端又把它塞到 `descriptions.operation` 中，与 applyDescriptionsToDom 的 expectations 不完全一致 |

## 6. 修复建议（仅列出思路，不实施）

1. 统一后端 complete-one 的响应结构：
   - 无论 LLM 返回什么，最终都应只输出一个扁平的 `{operation, parameters, responses}` 结构；
   - 对 `parsedMap` 含 `descriptions` 的情况做**下钻提取**（取 `parsedMap.get("descriptions")` 的内容而不是整段 putAll）；
   - 对纯文本 fallback：至少写入 `descriptions.put("operation", rawText)`，同时把 `ragHit / ragSummary` 作为独立字段返回，避免前端误以为有嵌套的 descriptions。
2. 前端 `applyDescriptionsToDom` 增加容错：
   - 支持 `descriptions.descriptions` 下钻；
   - 支持当 `descriptions` 本身就是 string（例如后端直接 `{descriptions:"..." }`）时走纯文本分支；
   - 对分支 A/B/C 都增加至少一条 toast（如“✅ 已补全 x 个描述”），避免用户无法判断是否成功。
3. `handleCompleteOne` 入口处不要依赖 state.pending（或设置自己独立的 lock 字段），避免被其他长耗时操作持续阻塞。
4. `btn == null` 场景下，即使没有 loading 按钮，也应至少调用一次 `showToast("✍️ 正在为该元素补全描述，请稍候…")`，给用户即时反馈。

## 7. 小结

- **现象**：点击 `🤖 补全描述` / 占位容器后 UI 无任何变化；后端日志正常记录 complete-one 完成；
- **根因**：① 后端 complete-one 的 JSON 响应结构与前端 `applyDescriptionsToDom` 期望的扁平结构不一致（存在 `descriptions.descriptions` 双层嵌套）；② 当 LLM 返回纯文本时 `descriptions` 仅含 `operation` 一个字段，parameters/responses 无法补全；③ `state.pending` 全局锁在其他异常路径下可能未被释放，导致后续点击被吞；④ 占位容器点击走 `handleCompleteOne(opblock, null)`，按钮状态更新被 `setButtonLoading` 吞掉，用户无任何反馈。
- **修复方向**：统一后端 complete-one 响应结构为扁平 JSON；前端增加容错与 toast；将 complete-one 的并发锁独立于其他长耗时操作。

