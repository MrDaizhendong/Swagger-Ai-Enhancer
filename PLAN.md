swagger-ai-enhancer 项目计划
1. 项目概述
将 AI 能力（LLM + RAG）注入 Swagger / OpenAPI 生态，实现 API 文档的智能增强。开发者只需引入 Maven 依赖，即可让 Swagger UI 自动补全接口描述、字段说明、错误码解释，并能一键生成面向不同角色（开发/产品/客户）的文档。

2. 核心原则
原则	说明
增强而非替代	不修改原版 Swagger UI 和 springdoc-openapi 的核心逻辑，只在其外围做增强
向后兼容	原版功能全部保留，用户即使不配 AI，表现和原版完全一致
数据注入模式	AI 在 OpenAPI JSON 生成后、Swagger UI 渲染前介入，修改的是数据而非源码
安全优先	AI 只生成描述文本，不修改代码、不执行系统命令。API Key 不落地前端
模块解耦	三个核心模块可独立使用、独立发布、独立部署
3. 分阶段计划
阶段一：基础架构搭建
目标：搭建 Maven 多模块项目骨架，各模块可独立编译，依赖关系正确。

任务清单：

创建根项目 swagger-ai-enhancer，配置聚合 POM

创建 5 个子模块：ui-starter、springdoc-starter、ai-starter、all-starter、demo

配置各模块的 pom.xml 依赖关系

验证 mvn clean compile 全部通过

Demo 模块能成功启动一个空的 Spring Boot 应用

验收标准：

根目录执行 mvn clean install 全部成功

Demo 模块启动后访问 http://localhost:8080 返回 200

阶段二：springdoc-starter 核心能力
目标：实现 OpenAPI JSON 的 AI 增强管道，这是整个项目最核心的能力。

任务清单：

依赖原版 springdoc-openapi，确保原版 /v3/api-docs 端点正常工作

实现 OpenApiEnhancer 类：拦截生成的 OpenAPI JSON，遍历所有 Path、Operation、Parameter、Schema

识别缺失 description 的元素，收集上下文信息（方法名、参数名、字段名、所在类名、已有 summary 等）

调用 AI 服务（先通过接口抽象，初期可用 Mock 实现）

将 AI 返回的描述填回 JSON，在扩展字段 x-ai-generated 标记为 true

暴露新端点 /v3/api-docs-enhanced

实现开关配置：swagger-ai-enhancer.springdoc.enhance-enabled=true/false

验收标准：

原版 /v3/api-docs 返回的 JSON 不受任何影响

/v3/api-docs-enhanced 返回的 JSON 中，缺失 description 的元素已被填充

AI 生成的内容带 x-ai-generated: true 标记

关闭增强开关后，/v3/api-docs-enhanced 与原版完全一致

阶段三：ai-starter 核心能力
目标：封装 LLM 调用、Prompt 管理、RAG 检索，提供统一的 AI 服务接口。

任务清单：

定义 LlmProvider 接口，实现 OpenAI、DeepSeek、阿里云百炼、Ollama、llama.cpp 五个提供者

实现 LlmProviderFactory：根据配置文件动态选择提供者

实现 Prompt 模板管理：补全描述、生成集成指南、生成产品说明书等

实现 RAG 服务：对接 Milvus，支持文档检索增强

暴露 Rest API：

POST /api/ai/complete-all：接受 OpenAPI JSON，返回补全后的 JSON

POST /api/ai/complete-one：接受单个接口上下文，返回补全描述

POST /api/ai/generate-guide：生成集成指南

POST /api/ai/generate-spec：生成产品说明书

实现内嵌模式和客户端模式切换

实现 API Key 安全存储（环境变量优先）

验收标准：

可通过配置切换不同的 LLM 提供者

可通过配置切换内嵌模式和客户端模式

所有 Rest API 可正常调用并返回预期结果

RAG 检索能返回相关文档片段

阶段四：ui-starter 核心能力
目标：增强版 Swagger UI，新增 AI 操作按钮和动态展示区域。

任务清单：

Fork 原版 Swagger UI 源码

新增全局操作栏组件：[🤖 一键补全所有描述] [📄 生成集成指南] [📋 生成产品说明书] [↩️ 恢复到原始文档]

新增接口级按钮：每个 Operation 旁增加 [🤖 补全描述]

实现动态描述容器：description 为空时显示占位区域

实现数据源双轨：优先加载 /v3/api-docs-enhanced，不可用时降级为 /v3/api-docs

实现按钮交互逻辑：点击后调 AI 接口，显示 loading，成功时填充内容，失败时显示错误

确保明暗模式兼容

打包为 WebJar / 静态资源

验收标准：

访问 /swagger-ui.html，看到增强版 UI

无增强端点时，AI 按钮自动隐藏

有增强端点时，点击按钮可触发 AI 生成并正确展示

原版所有功能不受影响

阶段五：all-starter + Demo 联调
目标：确保所有模块协同工作，Demo 项目可完整演示效果。

任务清单：

实现 all-starter：仅依赖三个子模块，无代码

Demo 项目引入 all-starter

Demo 项目编写示例 Controller（故意不写 description）

配置 AI 连接信息

端到端验证：启动 Demo → 访问 Swagger UI → 点击补全 → 看到 AI 生成内容

验收标准：

Demo 项目启动后，访问 Swagger UI 即可体验完整 AI 增强功能

所有 AI 生成内容正确、可读

作为新人上手的示例项目，5 分钟内可跑通

阶段六：部署与文档
目标：提供多种部署方式，完善文档。

任务清单：

编写 Dockerfile（UI 独立部署、AI 服务独立部署）

编写各模块的 README

编写用户接入指南

配置 CI/CD（Maven 发布、Docker 镜像构建）

编写贡献指南（方便后续向开源社区提交 PR）

验收标准：

Docker 镜像可成功构建并运行

文档清晰完整，新人可按文档独立完成接入

4. 技术栈
层级	技术	版本
后端框架	Spring Boot	3.x
接口文档生成	springdoc-openapi	2.5.x
AI 集成	Spring AI	1.0.x
向量存储	Milvus	Standalone
数据库	MySQL（默认）/ H2（开发）	—
前端	Swagger UI（React）	5.x
构建	Maven	3.9+
Java	17+	—
5. 交付物清单
制品	形态	发布渠道
swagger-ai-enhancer-ui-starter	Maven JAR	Maven 仓库
swagger-ai-enhancer-springdoc-starter	Maven JAR	Maven 仓库
swagger-ai-enhancer-ai-starter	Maven JAR	Maven 仓库
swagger-ai-enhancer-all-starter	Maven JAR	Maven 仓库
增强版 Swagger UI	Docker 镜像	Docker Registry
AI 公共服务	Docker 镜像 / JAR	Docker Registry
Demo 项目	源码	Git 仓库
