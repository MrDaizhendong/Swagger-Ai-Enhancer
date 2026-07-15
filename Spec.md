一、项目定位
swagger-ai-enhancer 将 AI 能力注入 Swagger/OpenAPI 生态，实现 API 文档的智能增强。开发者引入一个 Maven 依赖后，原本因缺少注解而干瘪的 Swagger 文档，能自动拥有完整的中文业务描述，并支持一键生成面向不同角色的文档。

核心原则：增强而非替代，向后完全兼容。 原版 Swagger UI 和 springdoc-openapi 的所有功能不受任何影响。

二、项目结构
Maven 多模块项目，共 5 个子模块：

模块	职责
swagger-ai-enhancer-ui-starter	增强版 Swagger UI 前端，新增 AI 操作按钮和动态展示区域
swagger-ai-enhancer-springdoc-starter	在 springdoc-openapi 生成 OpenAPI JSON 后插入 AI 增强管道
swagger-ai-enhancer-ai-starter	封装 LLM 调用、Prompt 管理、RAG 检索，提供 AI 服务 API
swagger-ai-enhancer-all-starter	聚合 Starter，无代码，仅依赖上述三个模块
swagger-ai-enhancer-demo	内置测试项目，用于开发期验证和效果演示
三、模块依赖关系
text
all-starter ──► ui-starter
all-starter ──► springdoc-starter
all-starter ──► ai-starter

demo ────────► all-starter
ui-starter 和 springdoc-starter 均可独立使用，不强制依赖 ai-starter。AI 能力可由 ai-starter 内嵌提供，也可通过配置指向远程 AI 服务。

ai-starter 可独立部署为公共服务，供非 Java 项目或跨项目复用。

all-starter 仅声明依赖，无任何代码，方便用户一键引入全部能力。

demo 引入 all-starter，作为端到端验证和示例。

四、数据流全景
整个增强流程分为五个阶段：

代码注解扫描：原版 springdoc-openapi 扫描用户 Controller 和实体上的 Swagger 注解

原始 JSON 生成：生成标准 OpenAPI 3.0 JSON，暴露在 /v3/api-docs

AI 增强管道：springdoc-starter 拦截原始 JSON，检测缺失 description 的元素，调用 AI 服务补全，生成增强版 JSON，暴露在 /v3/api-docs-enhanced

Swagger UI 渲染：ui-starter 提供的增强版 Swagger UI 优先加载增强版数据源，不可用时自动降级为原始数据源

用户交互：用户在 Swagger UI 页面上点击 AI 按钮，触发更多文档生成功能（集成指南、产品说明书等）

五、技术栈
层级	技术	说明
后端框架	Spring Boot 3.x	自动配置、依赖注入
接口文档	springdoc-openapi 2.x	生成 OpenAPI 3.0 规范
AI 集成	Spring AI	LLM 调用抽象、VectorStore 抽象
向量存储	Milvus Standalone	RAG 文档检索（可选）
前端	Swagger UI 5.x（React）	基于原版进行轻量改造
构建	Maven 多模块	聚合管理
Java	JDK 17+	LTS 版本
六、全局配置
所有配置项使用 swagger-ai-enhancer 前缀，定义在各自的 @ConfigurationProperties 类中。以下是全局结构：

yaml
swagger-ai-enhancer:
  enabled: true                    # 全局开关

  springdoc:
    enhance-enabled: true          # 是否启用 AI 增强管道
    enhanced-endpoint: /v3/api-docs-enhanced  # 增强版端点路径
    ai-generated-marker: true      # 是否在 AI 生成内容上添加标记
    marker-field: x-ai-generated   # 标记字段名

  ui:
    path: /swagger-ui.html         # Swagger UI 访问路径
    ai-buttons-enabled: true       # 是否显示 AI 操作按钮
    auto-detect-enhanced: true     # 是否自动检测增强端点

  ai:
    mode: embedded                 # 运行模式：embedded / client
    service-url: http://localhost:8081  # client 模式下的远程 AI 服务地址
    llm:
      provider: deepseek           # 当前使用的 LLM 提供者
      # 各提供者的具体配置（api-key、base-url、model、temperature 等）
    rag:
      enabled: true
      vector-store: milvus
      milvus:
        host: localhost
        port: 19530
        collection-name: swagger_knowledge
        top-k: 5
        similarity-threshold: 0.7
七、关键设计决策
决策	选择	理由
AI 注入点	springdoc 生成 JSON 后	数据注入比 UI 改造更彻底，不影响原版渲染逻辑
原版端点	保留 /v3/api-docs	向后兼容，用户可随时对比增强前后差异
增强版端点	新增 /v3/api-docs-enhanced	与原始端点并存，Swagger UI 优先读取增强版
AI 运行模式	内嵌 + 远程双模式	兼顾个人开发者和企业统一管理两种场景
LLM 接入	接口抽象 + 工厂模式	支持多种 LLM，切换无需修改核心代码
前端改造	插件式，不修改原版核心	便于升级上游 Swagger UI，降低合并冲突风险
AI 生成标记	在 JSON 扩展字段标记	用户可识别哪些内容是 AI 生成的，也可按标记过滤
八、交付物
制品	形态	用途
swagger-ai-enhancer-ui-starter	Maven JAR	增强版 Swagger UI，可嵌入或独立部署
swagger-ai-enhancer-springdoc-starter	Maven JAR	springdoc AI 增强管道
swagger-ai-enhancer-ai-starter	Maven JAR	AI 服务能力，可内嵌或独立部署
swagger-ai-enhancer-all-starter	Maven JAR	一键引入全部能力
swagger-ai-enhancer-demo	源码	测试与演示项目

模块1：swagger-ai-enhancer-ui-starter
1. 模块职责
提供增强版 Swagger UI 前端界面。基于原版 Swagger UI 进行轻量改造，新增 AI 功能触发入口与动态展示区域，同时保持与原版的完全兼容——当后端未提供 AI 增强能力时，表现与原版 Swagger UI 完全一致。

2. 核心功能
2.1 全局操作栏
在原版 Swagger UI 顶部区域新增按钮组：

按钮	功能	触发接口
🤖 一键补全所有描述	对整个 API 文档所有缺失 description 的元素调用 AI 补全	POST /api/ai/complete-all
📄 生成集成指南	基于当前 OpenAPI 规范生成面向开发者的集成指南	POST /api/ai/generate-guide
📋 生成产品说明书	基于当前 OpenAPI 规范生成面向产品的功能说明	POST /api/ai/generate-spec
↩️ 恢复到原始文档	丢弃所有 AI 生成内容，重新加载原始 OpenAPI 规范	重新加载 /v3/api-docs
交互要求：

调用 AI 接口时，按钮显示 loading 状态（如旋转图标），不可重复点击

生成成功后，用平滑过渡动画替换对应内容区域

生成失败时，显示错误提示（如 Toast），不阻断页面正常使用

“恢复到原始文档”需二次确认，避免误操作

2.2 接口级操作按钮
在每个接口（Operation）的详情区域内，新增 🤖 补全描述 按钮。点击后仅对该接口及其参数、响应中缺失 description 的元素调用 AI 补全，触发接口 POST /api/ai/complete-one。

按钮位置应在接口标题区域，与原有的 "Try it out" 按钮保持视觉协调。

2.3 动态描述容器
对于 OpenAPI JSON 中 description 字段为空字符串或 null 的接口、参数、Schema 属性，不再留白，而是渲染为虚线边框的占位区域，内含浅色提示文字（如“暂无描述，点击 🤖 补全”）。

当 AI 生成内容填充后，占位区域被替换为正常的描述文本，并在末尾附带 🤖 AI 生成 标记（灰色小字，可区别于人工编写的描述）。

2.4 数据源双轨加载
页面初始化时：

优先尝试请求 /v3/api-docs-enhanced

若返回 200，则使用增强版数据渲染，并激活所有 AI 按钮

若返回 404/500 或超时，则降级请求 /v3/api-docs，使用原版数据渲染，并隐藏所有 AI 按钮（因为无增强能力可用）

数据源选择逻辑对用户透明，仅在控制台输出日志区分。

2.5 离线兼容
当未检测到增强端点且 AI 服务不可达时，增强版 UI 的所有新增功能自动隐藏，页面表现与原版 Swagger UI 完全一致。不对原版功能产生任何干扰。

3. 接口约定
前端插件需要调用以下后端接口。所有接口的基础路径可配置（默认 /）。

接口	方法	请求体	响应体	说明
/v3/api-docs-enhanced	GET	-	OpenAPI JSON	增强版规范，由 springdoc-starter 提供
/api/ai/complete-all	POST	完整 OpenAPI JSON	补全后的 OpenAPI JSON	批量补全所有缺失描述
/api/ai/complete-one	POST	{ "path": "/api/users/{id}", "method": "GET", "context": { "operationSummary": "获取用户详情", "parameterNames": ["id", "fields"], "schemaProperties": { "id": "Long", "name": "String" } } }	{ "descriptions": { "operation": "根据用户ID获取用户详细信息...", "parameters": { "id": "用户唯一标识符", "fields": "需要返回的字段列表" } } }	单个接口补全
/api/ai/generate-guide	POST	完整 OpenAPI JSON	{ "markdown": "..." }	生成集成指南
/api/ai/generate-spec	POST	完整 OpenAPI JSON	{ "markdown": "..." }	生成产品说明书
4. 前端配置
通过页面 URL 参数或全局配置对象进行配置，以下为默认值：

配置项	默认值	说明
apiBaseUrl	/	AI 接口的基础路径
autoDetectEnhanced	true	是否自动检测增强端点
language	zh-CN	界面语言（预留）
5. 技术要求
基于原版 Swagger UI 源码进行修改

新增代码以插件形式存在（独立 JS/CSS 文件），不修改原版核心文件

新增 UI 元素需支持明暗模式（使用原版 CSS 变量体系）

动画使用 transition，缓动函数为 ease-in-out

AI 生成的文本渲染到 DOM 时需进行 HTML 转义，防止 XSS

6. 交付形态
方式	形态	用户如何使用
Maven 依赖	JAR（WebJar / 静态资源）	引入后访问 /swagger-ui.html
Docker 镜像	Nginx + 静态文件	docker run 后浏览器访问
纯静态文件	dist/ 压缩包	部署到任意 Web 服务器
7. 验收标准
原版 Swagger UI 所有功能正常（接口列表、Try it out、模型展示、认证等）

后端未提供 /v3/api-docs-enhanced 时，AI 按钮全部隐藏，页面与原版无异

后端提供增强端点时，缺失 description 处显示虚线占位容器

点击“一键补全所有描述”后，所有占位区域被 AI 生成内容替换，带有 🤖 AI 生成 标记

点击单个接口的“补全描述”后，仅该接口的描述被替换

点击“恢复到原始文档”后，页面回到未增强状态

AI 调用失败时，显示错误提示，不影响页面正常使用

明暗模式切换时，新增 UI 元素样式跟随变化

Docker 镜像可正常构建和运行

模块2：swagger-ai-enhancer-springdoc-starter
1. 模块职责
在 springdoc-openapi 生成 OpenAPI JSON 之后、暴露给 Swagger UI 之前，插入一个 AI 增强管道。检测所有缺失 description 的接口、参数和模型字段，调用 AI 服务自动补全中文业务描述，并将增强后的 JSON 通过新端点暴露出去。原版 springdoc-openapi 的所有功能保持不变。

2. 核心功能
2.1 原版端点保留
原版 /v3/api-docs 端点不受任何影响，返回未经 AI 处理的原始 OpenAPI JSON

原版 Swagger UI（/swagger-ui.html）仍可正常使用，不会因为本模块的存在而改变行为

2.2 增强端点新增
新增端点 /v3/api-docs-enhanced（路径可配置），返回 AI 增强后的 OpenAPI JSON。

增强端点的行为：

如果全局配置 swagger-ai-enhancer.enabled=false 或 springdoc.enhance-enabled=false，则该端点返回与原版完全一致的数据

如果 AI 服务不可用（内嵌模式下未配置 Key、客户端模式下网络不通），降级返回原版数据，并在日志中记录降级原因

增强过程不应显著增加端点响应时间（可通过缓存策略优化）

2.3 增强管道
在 springdoc 生成原始 OpenAPI JSON 后、返回给调用方之前，遍历整个 JSON 结构，对以下元素检测 description 是否为空：

元素	在 OpenAPI JSON 中的位置
接口（Operation）	paths.{path}.{method}.description
接口摘要	paths.{path}.{method}.summary（若为空也可补全，但优先级低于 description）
参数（Parameter）	paths.{path}.{method}.parameters[].description
请求体（RequestBody）	paths.{path}.{method}.requestBody.description
响应（Response）	paths.{path}.{method}.responses.{code}.description
模型字段（Schema）	components.schemas.{name}.properties.{field}.description
接口分组（Tag）	tags[].description
处理规则：

若 description 已有值（非空字符串），保留原值，不调用 AI

若 description 为空或不存在，收集该元素的上下文信息，调用 AI 生成描述并填充

所有 AI 生成的内容，在扩展字段 x-ai-generated 中标记为 true

2.4 AI 上下文收集
为每个缺失 description 的元素构建上下文信息，发送给 AI 服务。上下文应包含：

信息	说明	示例
元素类型	operation / parameter / schema 等	operation
元素名称	接口路径、参数名、字段名	/api/users/{id}、email
元素数据类型	参数的 Java 类型或 Schema 类型	String、Long
所属分组	所在的 Tag 名称	用户管理
已有摘要	如果 summary 有值，作为上下文传给 AI	获取用户详情
相关字段	同一接口下的其他参数名、同一 Schema 下的其他字段名	[id, name, email, phone]
2.5 AI 调用与缓存
通过接口调用 AI 服务（内嵌或远程），不直接依赖具体 LLM 实现

同一应用生命周期内，相同上下文的描述补全结果应缓存，避免重复调用 AI（缓存有效期可配置，默认 24 小时）

缓存内容不持久化，应用重启后清空

2.6 标记与可追溯
AI 生成的描述必须在 OpenAPI JSON 中明确标记，以区别于人工编写的描述。标记方式：

在包含 AI 生成描述的元素的同级，添加扩展字段 x-ai-generated: true

不修改原版 OpenAPI 规范中的任何标准字段结构

这样 Swagger UI 可以根据此标记，在渲染时添加视觉区分（如“🤖 AI 生成”标识）。

3. 配置项
配置前缀：swagger-ai-enhancer.springdoc

配置项	类型	默认值	说明
enhance-enabled	boolean	true	是否启用 AI 增强管道。关闭后 /v3/api-docs-enhanced 返回原版数据
enhanced-endpoint	String	/v3/api-docs-enhanced	增强版端点的访问路径
ai-generated-marker	boolean	true	是否在 AI 生成的内容上添加标记
marker-field	String	x-ai-generated	标记字段名
cache-enabled	boolean	true	是否启用 AI 结果缓存
cache-ttl-hours	int	24	缓存有效期（小时）
4. 关键细节
4.1 降级策略
AI 增强管道的设计原则是：增强功能绝不能破坏原有功能。因此在以下情况时必须降级：

AI 服务不可用（网络不通、超时、返回错误）

AI 服务返回的结果格式不符合预期

增强过程抛出未预期异常

降级时，/v3/api-docs-enhanced 返回与 /v3/api-docs 完全一致的数据，确保用户至少能看到原版文档。

4.2 需要覆盖的注解
本模块的增强管道处理的是 springdoc-openapi 已生成的 OpenAPI JSON 数据，因此不需要直接操作 Java 注解。但为了保证 Builder 理解处理范围，以下是需要关注的注解及其对应的 JSON 字段：

注解	对应 JSON 路径	补全目标
@Operation	paths.{path}.{method}.description	接口详细描述
@Operation(summary=...)	paths.{path}.{method}.summary	接口简要描述（若为空）
@Parameter	paths.{path}.{method}.parameters[].description	参数说明
@ApiResponse	paths.{path}.{method}.responses.{code}.description	响应说明
@Schema	components.schemas.{name}.properties.{field}.description	模型字段说明
@RequestBody	paths.{path}.{method}.requestBody.description	请求体说明
@Tag	tags[].description	接口分组说明
5. 验收标准
原版 /v3/api-docs 端点不受任何影响，返回数据与原版 springdoc 完全一致

/v3/api-docs-enhanced 端点正常返回，路径可配置

关闭 enhance-enabled 后，增强端点返回的数据与原版完全一致

增强端点返回的 JSON 中，原本缺失 description 的元素被填充了中文描述

AI 生成的内容在 JSON 中带有 x-ai-generated: true 标记

原本已有 description 的元素，其值未被修改

AI 服务不可用时，增强端点降级返回原版数据，无报错

相同上下文在缓存有效期内不重复调用 AI

增强过程不显著增加端点响应时间（相比原版增加不超过 2 秒，在 AI 调用环节是主要耗时点）

模块3：swagger-ai-enhancer-ai-starter
1. 模块职责
封装所有 AI 相关能力，包括 LLM 调用、Prompt 管理、RAG 检索增强，为其他模块（springdoc-starter、ui-starter）提供统一的 AI 服务接口。支持内嵌模式和独立部署两种运行方式。

2. 核心功能
2.1 LLM 提供者管理
支持以下 LLM 提供者，通过配置动态切换：

提供者	类型	说明
OpenAI	云端	GPT-4o 等模型
DeepSeek	云端	deepseek-chat 等模型
阿里云百炼	云端	通义千问系列模型
Ollama	本地	本地部署的开源模型
llama.cpp	本地	本地部署的开源模型
切换方式：用户修改配置文件中的 swagger-ai-enhancer.ai.llm.provider 即可，无需重启（若支持热加载，否则需重启）。

每个提供者需要配置：

api-key：API 密钥（云端必填，本地可不填）

base-url：API 端点地址（有默认值）

model：模型名称（有默认值）

timeout-seconds：请求超时时间（默认 60 秒）

max-tokens：最大生成 Token 数（默认 4096）

temperature：生成温度（默认 0.3，保证输出稳定性）

API Key 安全要求：

配置中必须支持环境变量占位符（如 ${DEEPSEEK_API_KEY}）

不得在日志中明文打印 API Key

2.2 Prompt 模板管理
预设以下 Prompt 模板，每个模板定义系统提示词和用户提示词的结构：

模板	用途	输入	输出
补全全部描述	批量补全整个 OpenAPI JSON 中所有缺失的 description	完整 OpenAPI JSON	补全后的 OpenAPI JSON
补全单个描述	补全单个接口或字段的 description	上下文信息对象	补全的描述文本
生成集成指南	基于 API 规范生成面向开发者的集成文档	完整 OpenAPI JSON	Markdown 文档
生成产品说明书	基于 API 规范生成面向产品的功能说明文档	完整 OpenAPI JSON	Markdown 文档
Prompt 要求：

所有模板生成的内容必须是中文

描述补全模板要求输出简洁、专业、符合技术文档风格

集成指南模板要求包含：快速开始、认证方式、核心接口说明、错误处理

产品说明书模板要求：忽略技术细节（如 JSON 结构），用业务语言描述每个接口的功能和场景

2.3 RAG 检索增强
支持在 AI 生成前，从知识库中检索相关文档片段，注入 Prompt 提升生成质量。

知识库来源：

用户自行准备的 Markdown 文档（如团队内部 API 设计规范、业务术语表）

文档通过离线脚本分割、嵌入后存入 Milvus

检索流程：

根据当前补全的上下文（接口名、参数名、所属模块等）构造查询文本

调用 Milvus 进行向量相似度检索，返回 Top-K 文档片段

过滤低于相似度阈值的片段

将片段拼接到 Prompt 的“参考资料”部分

配置项：

rag.enabled：是否启用 RAG

rag.vector-store：向量存储类型（默认 milvus）

rag.milvus.host/port/collection-name：Milvus 连接信息

rag.top-k：检索片段数量（默认 5）

rag.similarity-threshold：相似度阈值（默认 0.7）

降级策略：若 Milvus 不可用，跳过 RAG，直接使用基础 Prompt 生成，不影响主流程。

2.4 内嵌模式与客户端模式
模式	配置值	行为
内嵌模式	embedded	AI 能力在应用进程内运行，直接加载 LLM 提供者，直接访问 Milvus（若启用 RAG）
客户端模式	client	所有 AI 请求转发到远程 AI 服务（service-url），本模块仅作为轻量级 HTTP 客户端
模式切换：通过 swagger-ai-enhancer.ai.mode 配置切换，内嵌模式下 llm 配置块生效，客户端模式下 service-url 生效。

2.5 REST API 接口
以下接口由 ai-starter 提供，供 ui-starter 和 springdoc-starter 调用：

接口	方法	请求体	响应体	说明
/api/ai/complete-all	POST	完整 OpenAPI JSON	补全后的 OpenAPI JSON	批量补全所有缺失描述
/api/ai/complete-one	POST	上下文信息对象（见下方示例）	补全的描述文本集合	单个接口/字段补全
/api/ai/generate-guide	POST	完整 OpenAPI JSON	{ "markdown": "..." }	生成集成指南
/api/ai/generate-spec	POST	完整 OpenAPI JSON	{ "markdown": "..." }	生成产品说明书
/api/ai/health	GET	-	{ "status": "ok", "provider": "deepseek" }	健康检查
/api/ai/complete-one 请求体结构：

json
{
  "elementType": "operation",
  "path": "/api/users/{id}",
  "method": "GET",
  "context": {
    "operationSummary": "获取用户详情",
    "parameterNames": ["id", "fields"],
    "parameterTypes": { "id": "Long", "fields": "String" },
    "schemaProperties": { "id": "Long", "name": "String", "email": "String" },
    "tagName": "用户管理"
  }
}
响应体结构：

json
{
  "descriptions": {
    "operation": "根据用户ID获取用户的详细信息，包括姓名、邮箱、电话等",
    "parameters": {
      "id": "用户唯一标识符",
      "fields": "需要返回的字段列表，多个字段用逗号分隔"
    }
  }
}
/api/ai/complete-all 请求体：就是完整的 OpenAPI JSON 字符串。

响应体：补全后的完整 OpenAPI JSON，每个补全的元素添加 x-ai-generated: true。

3. 配置项
配置前缀：swagger-ai-enhancer.ai

配置项	类型	默认值	说明
mode	String	embedded	运行模式：embedded 或 client
service-url	String	http://localhost:8081	客户端模式下远程 AI 服务地址
llm.provider	String	deepseek	LLM 提供者标识
llm.openai.api-key	String	-	OpenAI API Key
llm.openai.base-url	String	https://api.openai.com/v1	OpenAI API 端点
llm.openai.model	String	gpt-4o	模型名称
llm.openai.timeout-seconds	int	60	请求超时（秒）
llm.openai.max-tokens	int	4096	最大生成 Token 数
llm.openai.temperature	double	0.3	生成温度
（DeepSeek、百炼、Ollama、llama.cpp 类似结构，省略）			
rag.enabled	boolean	true	是否启用 RAG
rag.vector-store	String	milvus	向量存储类型
rag.milvus.host	String	localhost	Milvus 主机
rag.milvus.port	int	19530	Milvus 端口
rag.milvus.collection-name	String	swagger_knowledge	集合名称
rag.top-k	int	5	检索片段数
rag.similarity-threshold	double	0.7	相似度阈值
4. 关键细节
4.1 提供者工厂
使用工厂模式根据配置动态创建 LLM 提供者实例。所有提供者实现同一接口，确保调用方无需关心具体实现。

4.2 降级策略
内嵌模式下，LLM 调用失败或超时，返回明确错误信息给调用方，由调用方决定如何处理（springdoc-starter 会降级返回原版数据）

客户端模式下，远程 AI 服务不可用，同样返回错误信息，不阻塞主流程

RAG 不可用时，跳过增强，直接使用基础 Prompt

4.3 扩展性
新增 LLM 提供者只需实现接口并注册到工厂

新增 Prompt 模板只需添加实现类

新增 RAG 向量存储（如 PGVector）只需实现接口

5. 验收标准
可通过配置切换不同的 LLM 提供者，且生成结果可用

可通过配置切换内嵌模式和客户端模式

/api/ai/complete-one 能根据上下文返回合理的中文描述

/api/ai/complete-all 能处理完整 OpenAPI JSON 并补全所有缺失描述

/api/ai/generate-guide 返回面向开发者的集成指南（Markdown）

/api/ai/generate-spec 返回面向产品的说明书（Markdown）

启用 RAG 时，Prompt 中包含检索到的知识片段

RAG 不可用时（Milvus 挂掉），降级为直接生成，不报错

LLM 调用失败时返回错误信息，不抛异常到调用方

内嵌模式下，API Key 不在日志中打印

模块可独立打成可执行 JAR 并启动

模块4：swagger-ai-enhancer-all-starter
1. 模块职责
聚合 Starter，本身不包含任何业务代码。唯一作用是将 ui-starter、springdoc-starter、ai-starter 三个模块打包为一个依赖，用户只需引入这一个依赖即可获得全部 AI 增强能力。

2. 核心功能
声明对三个子模块的依赖

提供一个空的自动配置类，用于触发 Spring Boot 自动配置机制加载三个子模块的配置

3. 验收标准
用户引入 all-starter 后，等同于同时引入了三个子模块

启动应用后，/swagger-ui.html 可用且为增强版 UI

/v3/api-docs-enhanced 端点可用

AI 能力正常工作（内嵌模式）或正确指向远程服务（客户端模式）

部署方案
1. 开发环境部署
用户（开发者）在本地开发时：

在项目的 pom.xml 中引入依赖（按需选择）：

xml
<!-- 方式一：一键引入全部 -->
<dependency>
    <groupId>com.swagger.ai</groupId>
    <artifactId>swagger-ai-enhancer-all-starter</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- 方式二：按需引入 -->
<dependency>
    <groupId>com.swagger.ai</groupId>
    <artifactId>swagger-ai-enhancer-springdoc-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- 其他模块同理 -->
在 application.yml 中配置 AI 连接信息：

yaml
swagger-ai-enhancer:
  ai:
    mode: embedded
    llm:
      provider: deepseek
      deepseek:
        api-key: ${DEEPSEEK_API_KEY}
启动应用，访问 http://localhost:8080/swagger-ui.html

2. AI 服务独立部署
当需要将 AI 能力作为团队公共服务时：

构建 AI 服务 JAR：

bash
cd swagger-ai-enhancer-ai-starter
mvn clean package
启动：

bash
java -jar target/swagger-ai-enhancer-ai-starter-1.0.0.jar --server.port=8081
其他项目使用客户端模式连接：

yaml
swagger-ai-enhancer:
  ai:
    mode: client
    service-url: http://ai-service.your-company.com:8081
3. Swagger UI 独立部署
当需要将增强版 Swagger UI 作为独立文档站部署时：

构建 Docker 镜像：

bash
cd swagger-ai-enhancer-ui-starter
docker build -t swagger-ai-ui .
运行：

bash
docker run -p 80:80 swagger-ai-ui
浏览器访问 http://localhost，在页面输入框中填入目标项目的 /v3/api-docs-enhanced 地址

4. Docker Compose 一键部署（全栈）
适用于完整演示环境，同时启动 AI 服务和增强版 UI：

yaml
version: '3.8'
services:
  ai-service:
    build: ./swagger-ai-enhancer-ai-starter
    ports:
      - "8081:8081"
    environment:
      - DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
  
  swagger-ui:
    build: ./swagger-ai-enhancer-ui-starter
    ports:
      - "80:80"
    environment:
      - AI_SERVICE_URL=http://ai-service:8081

模块5：swagger-ai-enhancer-demo
1. 模块职责
作为项目内置的测试与演示应用，供开发者验证整套 AI 增强能力是否正常工作，也作为新人上手示例。引入 all-starter，启动后即可体验完整 AI 增强效果。

2. 核心功能
提供标准的 Spring Boot Web 应用

引入 swagger-ai-enhancer-all-starter，获得全部 AI 增强能力

包含故意不写 description 的示例 Controller 和 Entity，用于验证 AI 自动补全功能

启动后访问 /swagger-ui.html 即可看到增强版 Swagger UI，且可立即体验 AI 补全功能

3. 示例接口要求
至少包含 5 个接口，覆盖完整 CRUD 场景。所有接口和字段故意不写 description，只保留最基本的 summary：

接口	方法	路径	说明
获取用户详情	GET	/api/users/{id}	@Operation(summary="获取用户详情")，不写 description。路径参数 id、查询参数 fields 不写 description
创建用户	POST	/api/users	@Operation(summary="创建用户")，不写 description。@RequestBody 不写 description
更新用户	PUT	/api/users/{id}	@Operation(summary="更新用户信息")，不写 description
删除用户	DELETE	/api/users/{id}	@Operation(summary="删除用户")，不写 description
用户列表	GET	/api/users	@Operation(summary="获取用户列表")，分页参数 page、size 不写 description
示例实体 User 包含字段：id、name、email、phone、status、createdAt。所有字段不写 @Schema(description=...)。

可额外添加一个 @Tag(name="用户管理") 但不写 description，用于验证 Tag 级别的补全。

4. 配置要求
用户启动前需配置 AI 连接信息（通过环境变量或 application.yml）：

yaml
swagger-ai-enhancer:
  ai:
    mode: embedded
    llm:
      provider: deepseek
      deepseek:
        api-key: ${DEEPSEEK_API_KEY:your-key-here}
5. 验收标准
启动应用后，访问 /swagger-ui.html 能正常看到 Swagger UI 界面

页面为增强版 UI（顶部有 AI 操作按钮栏）

接口列表包含上述 5 个示例接口，按 Tag 分组

未配置有效 AI Key 时，缺失 description 处显示占位提示

配置有效 AI Key 后，点击“一键补全所有描述”或单个接口的“补全描述”按钮，空白区域被 AI 生成的中文描述填充

AI 生成内容带有明显标记（如 🤖 AI 生成 或虚线边框区分）

点击“恢复到原始文档”可回到未增强状态

原版 Swagger UI 的 Try it out 功能正常可用

