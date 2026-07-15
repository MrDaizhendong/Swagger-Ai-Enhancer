swagger-ai-enhancer 编码规范与质量准则
1. 适用范围
本规范适用于 swagger-ai-enhancer 项目的所有模块，包括 Java 后端代码、前端 JavaScript/React 代码、SQL 脚本、配置文件等。所有贡献者和 Builder 必须遵守。

2. Java 编码规范
2.1 命名规范
元素	规范	示例
包名	全小写，点分隔，使用项目根包 com.swagger.ai.enhancer	com.swagger.ai.enhancer.springdoc
类名	大驼峰，名词或名词短语	OpenApiEnhancer, LlmProviderFactory
接口名	大驼峰，形容词或名词	LlmProvider, DescriptionEnricher
方法名	小驼峰，动词或动词短语	enhanceDescription(), getProvider()
常量	全大写，下划线分隔	MAX_RETRY_COUNT
变量	小驼峰，名词	openApiJson, missingDescriptions
枚举	大驼峰，枚举值全大写	LlmProviderType.OPENAI
2.2 类与方法设计
单一职责：每个类只做一件事。如 OpenApiEnhancer 只负责遍历和填充，不负责调用 LLM。

依赖注入：使用构造器注入，避免字段注入 @Autowired。

接口抽象：所有 AI 调用、存储操作必须通过接口定义，方便替换实现。

不可变性：参数对象、配置对象使用 final 字段，通过构造器初始化。

空值处理：所有方法返回值不允许返回 null，使用 Optional 或空集合（Collections.emptyList()）。

异常处理：

AI 调用失败时，降级返回原始数据，不能阻断主流程。

自定义异常类继承 RuntimeException，提供有意义的错误消息。

记录异常日志，使用 @Slf4j 或 LoggerFactory。

线程安全：共享资源使用 ConcurrentHashMap 或同步块保护。AI 客户端实例应线程安全（Spring AI 默认提供）。

2.3 配置管理
所有配置项定义在 @ConfigurationProperties 类中，前缀 swagger-ai-enhancer。

提供合理的默认值，确保零配置可用（如 AI 增强默认开启，但无 AI 配置时自动降级）。

敏感信息（API Key）必须通过 ${ENV_VAR} 或外部配置源注入，不得硬编码或提交到代码仓库。

配置项必须添加 Javadoc 说明。

2.4 日志与可观测性
日志框架使用 SLF4j。

关键步骤记录 info 日志（如“开始增强 OpenAPI JSON”，“增强完成，共处理 {} 个缺失描述”）。

异常记录 error 日志，包含完整堆栈信息。

AI 调用耗时、成功率等指标通过 Micrometer 暴露（可选，但应预留接口）。

2.5 测试要求
单元测试覆盖率 ≥ 70%。

使用 JUnit 5 + Mockito。

测试类命名：被测类名Test。

AI 相关测试必须使用 Mock，不实际调用外部 LLM。

集成测试放在 src/test/java/.../integration 包下，使用 @SpringBootTest。

3. 前端编码规范（JavaScript/React）
3.1 命名规范
元素	规范	示例
组件文件	大驼峰，与组件名一致	AiGlobalToolbar.jsx
工具函数	小驼峰	fetchEnhancedSpec()
常量	全大写，下划线分隔	AI_BUTTON_LABEL
CSS 类名	BEM 风格或小写连字符	.swagger-ai__btn
3.2 组件设计
优先使用函数组件 + Hooks。

副作用（API 调用）放在 useEffect 中，注意清理。

状态管理使用 Redux 或 React Context，避免过度 prop drilling。

错误边界使用 ErrorBoundary 包裹可能出错的 AI 组件。

3.3 API 调用
统一封装 fetch 或 axios，添加超时、重试、错误处理。

AI 接口调用前，检查服务可用性，不可用时隐藏按钮。

加载状态必须展示 loading 动画，成功/失败有明确反馈。

3.4 样式与主题
使用原版 Swagger UI 的 CSS 变量，不覆盖核心样式。

新增组件支持暗色模式，通过 CSS 变量适配。

动画使用 transition 或 framer-motion，缓动函数统一为 ease-in-out。

4. 数据库规范（ai-starter 可选模块）
4.1 命名规范
元素	规范	示例
表名	小写，下划线分隔，复数形式	llm_call_logs, rag_documents
列名	小写，下划线分隔	created_at, api_provider
索引名	idx_表名_列名	idx_llm_call_logs_provider
主键	统一使用 id，自增 BIGINT	id BIGINT AUTO_INCREMENT PRIMARY KEY
4.2 表设计
必须包含 created_at（默认 CURRENT_TIMESTAMP）和 updated_at（ON UPDATE CURRENT_TIMESTAMP）。

使用 InnoDB 引擎，字符集 utf8mb4。

所有列添加 COMMENT。

禁止使用外键约束，关联逻辑在应用层处理。

4.3 SQL 编写
关键字大写：SELECT, FROM, WHERE。

避免 SELECT *，指定需要的列。

参数化查询，防止 SQL 注入。

5. 安全规范
领域	规范
API Key	通过环境变量或外部配置注入，不提交到 Git。日志中不能打印 Key。
输入验证	对所有外部输入（OpenAPI JSON、用户自然语言查询）进行校验和清洗，防止注入攻击。
输出编码	AI 生成的文本渲染到 HTML 时，必须进行转义，防止 XSS。
认证授权	UI 可选 Basic Auth，由配置开关控制。AI 服务接口可配 API Key 认证。
依赖安全	定期扫描依赖漏洞（如 Dependabot），禁止使用有严重漏洞的库。
CORS	仅允许配置的来源访问 API，默认不允许跨域。
6. 可扩展性设计要求
插件化：AI 增强管道采用责任链模式，可插入多个 DescriptionEnricher。

策略模式：LLM 提供者、RAG 检索策略均可动态替换。

事件驱动：关键操作（如描述补全完成）可发布 Spring Event，方便后续扩展通知、审计等功能。

版本管理：OpenAPI 增强后需标记版本 x-ai-enhanced-version: "1.0"，便于未来兼容。

7. 代码风格与工具
Java：使用 Lombok（@Data, @Builder, @Slf4j），但避免滥用 @AllArgsConstructor 在复杂实体上。

格式化：统一使用项目根目录的 formatter.xml（Eclipse/IDEA 格式），2 空格缩进。

静态检查：集成 Checkstyle 或 SonarLint，提交前通过检查。

前端：使用 ESLint + Prettier，缩进 2 空格。

8. 构建与部署规范
Maven：父 POM 版本管理使用 dependencyManagement，子模块不指定版本号。

多环境：application.yml 提供默认配置，通过 profile（dev, prod）切换环境。

Docker：所有 Dockerfile 使用多阶段构建，基础镜像使用 eclipse-temurin:17-jre。

版本号：遵循语义化版本 MAJOR.MINOR.PATCH，首次发布 1.0.0。