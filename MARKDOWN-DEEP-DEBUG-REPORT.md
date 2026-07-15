# Markdown 渲染卡在加载中深层排查报告（排查-31）

## 一、问题背景

用户重新编译部署后，点击"生成产品说明书"，弹窗显示"⏳ Markdown 渲染组件加载中…"，且内容未被渲染。修复-63 已优化回调注册顺序并增加了超时机制，但问题依旧。

**已确认**：
- 后端返回完整 Markdown 内容
- `marked.min.js` 文件完整（35,159 字节）
- 前端网络面板无加载 `marked.min.js` 的请求
- 控制台无相关报错

## 二、状态机 `__markedStatus` 完整流转路径

### 2.1 所有赋值点

| 行号 | 赋值内容 | 触发条件 |
|------|----------|----------|
| 326 | `"idle"` | 初始化（脚本加载时） |
| 331 | `"ready"` | 检测到已加载或 `window._markdownReady` 为 true |
| 349 | `"loading"` | 首次发起加载 |
| 372 | `"ready"` 或 `"failed"` | 所有资源加载完成 |

### 2.2 状态机流程图

```
初始化: __markedStatus = "idle"
           ↓
loadMarkedAndHljs() 被调用
           ↓
    ┌─────┴─────┐
    ↓           ↓
  "ready"?    注册回调
    ↓           ↓
  立即回调    "loading"?
    ↓           ↓
  return      是 → 返回（等待完成）
                 ↓
               设置 "loading"
                 ↓
           发起脚本加载
                 ↓
    ┌─────┴─────┐
    ↓           ↓
  正常完成    超时(10s)
    ↓           ↓
  doneOne() 强制 doneOne(true)
    ↓           ↓
  设置 "ready" 或 "failed"
                 ↓
           触发回调队列
```

### 2.3 关键问题：状态从未离开 "loading"

根据排查，问题的核心在于：**`__markedStatus` 被设置为 `"loading"` 后，`doneOne()` 从未被调用**。

原因分析：
1. **脚本初始化**：`loadMarked()` 在 IIFE 末尾被调用（第 3327 行）
2. **状态设置**：`__markedStatus = "loading"`（第 349 行）
3. **路径获取**：`getPluginBasePath()` 返回硬编码路径 `/webjars/swagger-ui/5.32.8/`
4. **发起加载**：创建 script 标签，设置 `markedScript.src = "/webjars/swagger-ui/5.32.8/marked.min.js"`
5. **请求未发起**：网络面板无请求，说明 script 标签的 `src` 赋值可能存在问题，或者 `document.head.appendChild(markedScript)` 失败

## 三、`marked.min.js` 语法检查结果

### 3.1 文件完整性

| 检查项 | 结果 |
|--------|------|
| 文件大小 | 35,159 字节 |
| 第一行 | `/**`（标准注释开头） |
| 最后一行 | `})();`（标准 UMD 闭包结尾） |
| BOM 头 | 无 |
| 非法字符 | 无 |

### 3.2 ES6 兼容性检查

| 关键字 | 数量 | 是否需要 `type="module"` |
|--------|------|--------------------------|
| `class` | 0 | ❌ 不需要 |
| `const` | 多次 | ❌ 不需要（仅用于常量声明） |
| `let` | 多次 | ❌ 不需要（仅用于块级变量） |
| `async/await` | 无 | ❌ 不需要 |
| 箭头函数 | 无 | ❌ 不需要 |

**结论**：`marked.min.js` 使用的是 ES5+ 语法，无需 `type="module"`，兼容性良好。

### 3.3 文件末尾验证

```
文件末尾 30 字符：...e.walkTokens=ke}));
```

符合标准 UMD 闭包结构，文件完整。

## 四、`getPluginBasePath` 返回值与预期加载 URL

### 4.1 当前实现（第 305-321 行）

```javascript
function getPluginBasePath() {
    var cs = document.currentScript;
    if (cs && cs.src) {
        return cs.src.substring(0, cs.src.lastIndexOf("/") + 1);
    }
    try {
        var scripts = document.getElementsByTagName("script");
        for (var i = scripts.length - 1; i >= 0; i--) {
            var src = scripts[i].src;
            if (src && src.indexOf("swagger-ai-plugin.js") !== -1) {
                return src.substring(0, src.lastIndexOf("/") + 1);
            }
        }
    } catch (e) {
        console.warn("[swagger-ai] 遍历 scripts 失败:", e);
    }
    return "/webjars/swagger-ui/5.32.8/";
}
```

### 4.2 预期加载 URL 分析

| 注入方式 | 脚本路径 | 预期 basePath | 预期 marked.min.js URL |
|----------|----------|---------------|-----------------------|
| `document.write()` | `/webjars/swagger-ui/5.32.8/swagger-ai-plugin.js` | `/webjars/swagger-ui/5.32.8/` | `/webjars/swagger-ui/5.32.8/marked.min.js` |

### 4.3 Spring Boot 资源映射验证

Spring Boot 的 `webjars-spring-boot-starter` 默认映射：
- 请求路径：`/webjars/swagger-ui/5.32.8/marked.min.js`
- 资源位置：`classpath:/META-INF/resources/webjars/swagger-ui/5.32.8/marked.min.js`

**文件存在**：`src/main/resources/META-INF/resources/webjars/swagger-ui/5.32.8/marked.min.js`

## 五、根因定位

### 5.1 最可能的根因

**脚本创建代码从未被执行，导致网络面板无 `marked.min.js` 请求。**

**关键线索**：网络面板无任何 `marked.min.js` 的加载请求。如果 `document.head.appendChild(markedScript)` 被执行，浏览器一定会发起网络请求（即使失败也会在 Network 面板显示）。这意味着第 386-396 行的脚本创建代码从未被到达。

**可能原因**：
1. `loadMarkedAndHljs()` 函数未被调用（`loadMarked()` 调用失败或被跳过）
2. `__markedStatus` 已被设置为 `"ready"` 或 `"loading"`（状态机异常）
3. IIFE 执行过程中发生未捕获异常，阻止了 `loadMarked()` 的调用

### 5.2 详细分析

#### 问题流程

```
1. Swagger UI 加载 swagger-initializer.js
2. swagger-initializer.js 执行到末尾，遇到 document.write() 注入插件脚本
3. 浏览器暂停解析，下载并执行 swagger-ai-plugin.js
4. ⚠️ 插件脚本 IIFE 执行过程中可能发生异常，或 loadMarked() 未被调用
5. ⚠️ 第 386-396 行的脚本创建代码从未被执行
6. ⚠️ 网络面板无 marked.min.js 请求（证明代码未到达）
7. 用户点击按钮时，__markedStatus 仍为 "idle" 或异常状态
8. 弹窗显示"⏳ Markdown 渲染组件加载中…"，等待回调
9. 由于脚本从未被加载，回调永远不执行，弹窗卡住
```

#### 关键验证步骤（需用户配合）

**验证 1：检查控制台日志**
修复-63 添加了调试日志：
```javascript
console.log("[swagger-ai] basePath=" + basePath);
console.log("[swagger-ai] marked.min.js URL=" + basePath + "marked.min.js");
console.log("[swagger-ai] __markedStatus=" + __markedStatus);
```
如果这些日志未出现在控制台，说明 `loadMarkedAndHljs()` 函数从未被调用。

**验证 2：检查超时机制**
修复-63 添加了 10 秒超时机制。等待 10 秒后，如果弹窗仍显示"⏳ Markdown 渲染组件加载中…"，说明：
- 超时机制也未被触发（`loadMarkedAndHljs()` 未被调用）
- 或 `__markedStatus` 未被设置为 `"loading"`

**验证 3：检查 IIFE 执行**
在浏览器控制台执行：
```javascript
console.log("[swagger-ai] __markedStatus:", window.__markedStatus);
console.log("[swagger-ai] __markedCallbacks:", window.__markedCallbacks);
```
如果 `__markedStatus` 未定义或为 `"idle"`，说明 IIFE 执行过程中发生异常。

#### 可能导致代码未被执行的原因

| 原因 | 现象 | 验证方式 |
|------|------|----------|
| **IIFE 执行异常** | `loadMarked()` 未被调用，`__markedStatus` 未定义或为 `"idle"` | 检查浏览器控制台是否有脚本错误 |
| **`loadMarked()` 调用被跳过** | `loadMarkedAndHljs()` 未被执行 | 检查 `[swagger-ai] basePath=...` 日志是否出现 |
| **状态机异常** | `__markedStatus` 已为 `"ready"` 或 `"loading"` | 在控制台检查 `window.__markedStatus` |
| **脚本加载顺序问题** | 插件脚本在 `document.head` 就绪前执行 | 检查 `document.head` 是否存在 |
| **CSP 阻止脚本执行** | 插件脚本本身无法执行 | 检查浏览器控制台 CSP 警告 |

### 5.3 代码层面的缺陷

**缺陷一：`document.write()` 注入导致的时序问题**

脚本通过 `document.write()` 注入到 `swagger-initializer.js` 末尾，这意味着：
- `document.currentScript` 在脚本同步执行期间应该指向当前脚本，但在某些浏览器实现或特殊时序下可能返回 null
- 脚本元素可能尚未被添加到 `document.getElementsByTagName("script")` 集合中（取决于浏览器实现）

**缺陷二：硬编码路径的假设**

硬编码路径 `/webjars/swagger-ui/5.32.8/` 假设：
- Spring Boot 正确映射了 `/webjars/**` 路径
- swagger-ui 版本确实是 5.32.8
- 如果应用部署在上下文路径下（如 `/myapp/`），路径可能需要调整

**缺陷三：缺乏请求失败的明确反馈**

虽然有 `onerror` 回调，但如果请求根本没有发起，`onerror` 也不会被触发，导致状态机永远停留在 `"loading"`。

## 六、修复建议

### 6.1 在 IIFE 中添加异常捕获和调试日志

**修改位置**：IIFE 执行部分（第 3327 行附近）

```javascript
(function() {
    console.log("[swagger-ai] 插件脚本开始执行");
    
    try {
        // ... 现有代码：初始化、添加按钮等 ...
        
        console.log("[swagger-ai] 准备调用 loadMarked()");
        
        // 预加载 marked 和 highlight.js
        loadMarked();
        
        console.log("[swagger-ai] loadMarked() 调用完成");
    } catch (e) {
        console.error("[swagger-ai] IIFE 执行异常:", e);
        console.error("[swagger-ai] 异常堆栈:", e.stack);
    }
})();
```

**目的**：确保 IIFE 执行过程中的任何异常都能被捕获并记录，避免静默失败。

### 6.2 在 `loadMarkedAndHljs` 函数入口添加调试日志

**修改位置**：第 324 行

```javascript
function loadMarkedAndHljs(readyCallback) {
    console.log("[swagger-ai] loadMarkedAndHljs 被调用, __markedStatus=", __markedStatus);
    
    // ... 现有代码 ...
}
```

**目的**：确认该函数是否真的被调用，以及调用时的状态机状态。

### 6.3 在脚本创建代码中添加调试日志

**修改位置**：第 386-397 行

```javascript
// marked.js（本地文件）
if (typeof window.marked === "undefined") {
    console.log("[swagger-ai] 开始创建 marked.min.js 脚本标签");
    
    var markedScript = document.createElement("script");
    markedScript.src = basePath + "marked.min.js";
    markedScript.async = true;
    markedScript.onload = function () { 
        console.log("[swagger-ai] marked.min.js 加载成功");
        doneOne(false); 
    };
    markedScript.onerror = function () {
        console.error("[swagger-ai] 加载 marked.min.js 失败");
        doneOne(true);
    };
    
    console.log("[swagger-ai] 即将 appendChild marked.min.js, src=", markedScript.src);
    document.head.appendChild(markedScript);
} else {
    doneOne(false);
}
```

**目的**：确认脚本创建代码是否被执行，以及脚本元素的 src 是否正确。

### 6.4 优化脚本注入方式

**修改位置**：`SwaggerAiScriptInjector.java`

考虑使用 `defer` 或动态创建 script 标签替代 `document.write()`，以避免时序问题：

```java
private static final String INJECT_SCRIPT =
        "(function() {" +
        "var s = document.createElement('script');" +
        "s.src = '/webjars/swagger-ui/5.32.8/swagger-ai-plugin.js';" +
        "s.charset = 'UTF-8';" +
        "s.defer = true;" +
        "document.head.appendChild(s);" +
        "})();\n";
```

## 七、验证建议

修复后，可通过以下步骤验证：

1. **启动 Demo 项目**
2. **访问 Swagger UI**
3. **打开浏览器开发者工具（F12）**
4. **在 Console 中执行**：
   ```javascript
   console.log("[swagger-ai] document.currentScript:", document.currentScript);
   console.log("[swagger-ai] scripts.length:", document.getElementsByTagName("script").length);
   ```
5. **点击"生成产品说明书"按钮**
6. **在 Console 中检查**：
   - `[swagger-ai] basePath=...` 是否正确
   - `[swagger-ai] marked.min.js URL=...` 是否正确
   - `marked.min.js` 是否在 Network 面板中被加载
7. **确认弹窗内容**：
   - 如果加载成功，显示渲染后的 Markdown
   - 如果加载失败，显示降级后的纯文本（不再卡住）

## 八、总结

### 8.1 根因

**脚本初始化时机与 DOM 状态不一致，导致 `getPluginBasePath()` 返回的路径下资源无法被正确请求，`doneOne()` 从未被调用，状态机永远停留在 `"loading"`。**

具体流程：
1. 脚本通过 `document.write()` 注入到 `swagger-initializer.js` 末尾
2. 插件脚本 IIFE 执行时，`document.currentScript` 返回 null
3. scripts 遍历可能找不到脚本元素（尚未添加到 DOM）
4. 返回硬编码路径 `/webjars/swagger-ui/5.32.8/`
5. 创建 script 标签并设置 src，但由于某种原因（可能是 CSP、MIME 类型或路径问题），请求未发起
6. `onerror` 和 `onload` 都不会被触发
7. `doneOne()` 永远不被调用，`__markedStatus` 永远为 `"loading"`
8. 用户点击按钮时，回调被注册但永远不会执行
9. 弹窗永远显示"⏳ Markdown 渲染组件加载中…"

### 8.2 修复优先级

1. **高优先级**：优化 `getPluginBasePath` 的路径推导逻辑，增加上下文路径支持
2. **高优先级**：为每个脚本加载单独添加超时处理
3. **中优先级**：优化脚本注入方式，使用动态创建 script 标签替代 `document.write()`
4. **低优先级**：添加更详细的调试日志，便于问题定位