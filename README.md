# swagger-ai-enhancer

![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)
![Java Version](https://img.shields.io/badge/java-21+-orange)
![Spring Boot](https://img.shields.io/badge/spring%20boot-3.2+-green)

将 AI 能力注入 Swagger / OpenAPI 生态，实现 API 文档的智能增强。开发者只需引入一个 Maven 依赖，即可让原本缺少描述的 Swagger 文档自动拥有完整的中文业务描述，并支持一键生成面向不同角色的专业文档。

**核心原则**：增强而非替代，向后完全兼容。原版 Swagger UI 和 springdoc-openapi 的所有功能不受任何影响。

---

## 目录

- [项目亮点](#项目亮点)
- [技术架构](#技术架构)
- [项目结构](#项目结构)
- [核心功能](#核心功能)
- [前端使用指南](#前端使用指南)
- [RAG 部署指南](#rag-部署指南)
- [快速开始](#快速开始)
- [部署方案](#部署方案)
- [配置说明](#配置说明)
- [API 接口列表](#api-接口列表)
- [Demo 示例接口](#demo-示例接口)
- [注意事项](#注意事项)
- [开源协议](#开源协议)
- [贡献指南](#贡献指南)
- [更新日志](#更新日志)

---

## 项目亮点

### RAG 全链路增强

**Score 输出**：每条检索结果附带相似度分数（如 `[相似度: 0.89]`），便于判断相关性。

**分层过滤**：
- 高相关（≥0.7）标记 ✅，优先参考
- 中相关（0.4-0.7）标记 ⚠️，仅供参考
- 低相关（<0.4）自动过滤，不注入 Prompt

**动态标注**：文档末尾自动生成"📊 知识库参考情况"标注，说明检索命中数量和使用情况。

**Token 溢出保护**：三级降级链路，确保大项目也能稳定生成：
1. 详细描述 Token 超限 → 降级为精简描述
2. 精简大纲 Token 超限 → 跳过润色阶段
3. 跳过润色后仍超限 → 返回分组合并结果

**按需探测**：首次生成文档时自动探测模型上下文限制，无需用户手动配置。探测失败时使用保守默认值 131072 tokens 兜底。

**多知识库隔离**：不同文档类型使用独立的 RAG 集合（如 `swagger_knowledge_product_doc`、`swagger_knowledge_requirement_doc`），实现知识隔离。

**增量同步**：支持文档的增量更新，无需全量重建索引，提高同步效率。

**四种向量数据库可插拔**：Milvus、Qdrant、PgVector、Weaviate，通过配置动态切换。

### Skill 文档系统

- 9 个 Skill 文档覆盖所有生成场景
- XML 标签分隔法（`<skill>...</skill>`）确保 LLM 正确区分指令和参考内容，消除 Skill 泄露问题
- Prompt 模板与 Skill 文档格式统一，消除指令冲突
- Skill 内容清理 Markdown 标题，改为纯文本格式，避免 LLM 误判为输出模板

### 六种文档类型支持

- 📄 集成指南（面向开发者）
- 📋 产品说明书（面向产品）
- 📝 需求文档（面向业务）
- 📦 交付文档（面向运维）
- 🧪 测试用例（面向测试）
- 🔧 API 接口补全（单接口/单参数）

### 超大型项目分组生成方案

采用业界成熟的 Map-Reduce 模式（参考 LlamaIndex TreeSummarize 和 LangChain Map-Reduce）：

1. **按 Tag 分组**：自动按 OpenAPI Tag 分组，避免单 Prompt 过长
2. **并行精简**：对每个分组的 OpenAPI JSON 进行结构精简，降低 Token 消耗
3. **并行生成**：多个分组并行调用 LLM，提升生成效率
4. **Refine 润色**：合并所有分组结果后，生成整体文档概述，优化结构一致性

支持开关控制：可关闭润色阶段以提高响应速度。

---

## 技术架构

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.3.0 |
| 接口文档 | springdoc-openapi | 2.5.0 |
| AI 集成 | Spring AI | 1.0.0-M4 |
| 向量存储 | Milvus / Qdrant / PgVector / Weaviate | 见下方 |
| 前端 | Swagger UI | 5.x (React) |
| 构建 | Maven | 3.9+ |
| Java | JDK | 17+ |

**向量数据库版本**：
| 数据库 | SDK 版本 |
|--------|----------|
| Milvus | 2.5.0 |
| Qdrant | 1.10.0 |
| Weaviate | 4.8.0 |
| PgVector | 0.1.4 |

---

## 项目结构

```
swagger-ai-enhancer/
├── swagger-ai-enhancer-ui-starter          # 增强版 Swagger UI 前端
├── swagger-ai-enhancer-springdoc-starter   # springdoc AI 增强管道
├── swagger-ai-enhancer-ai-starter          # AI 服务能力（LLM + RAG）
├── swagger-ai-enhancer-ai-starter-milvus   # Milvus 向量库实现
├── swagger-ai-enhancer-ai-starter-qdrant   # Qdrant 向量库实现
├── swagger-ai-enhancer-ai-starter-pgvector # PgVector 向量库实现
├── swagger-ai-enhancer-ai-starter-weaviate # Weaviate 向量库实现
├── swagger-ai-enhancer-all-starter         # 聚合 Starter（一键引入）
└── swagger-ai-enhancer-demo                # 演示项目
```

### 模块职责

| 模块 | 职责 |
|------|------|
| **ui-starter** | 增强版 Swagger UI，新增 AI 操作按钮和动态展示区域 |
| **springdoc-starter** | 在 springdoc-openapi 生成 JSON 后插入 AI 增强管道 |
| **ai-starter** | 封装 LLM 调用、Prompt 管理、RAG 检索，提供 AI 服务 API |
| **ai-starter-milvus** | Milvus 向量存储实现（可选） |
| **ai-starter-qdrant** | Qdrant 向量存储实现（可选） |
| **ai-starter-pgvector** | PgVector 向量存储实现（可选） |
| **ai-starter-weaviate** | Weaviate 向量存储实现（可选） |
| **all-starter** | 聚合模块，无代码，仅依赖上述三个核心模块 |
| **demo** | 内置测试项目，用于开发期验证和效果演示 |

### 模块依赖关系

```
all-starter ──► ui-starter
all-starter ──► springdoc-starter
all-starter ──► ai-starter

ai-starter ──► ai-starter-milvus (可选)
ai-starter ──► ai-starter-qdrant (可选)
ai-starter ──► ai-starter-pgvector (可选)
ai-starter ──► ai-starter-weaviate (可选)

demo ────────► all-starter
```

### 数据流全景

1. **代码注解扫描**：原版 springdoc-openapi 扫描用户 Controller 和实体上的 Swagger 注解
2. **原始 JSON 生成**：生成标准 OpenAPI 3.0 JSON，暴露在 `/v3/api-docs`
3. **AI 增强管道**：springdoc-starter 拦截原始 JSON，检测缺失 description 的元素，调用 AI 服务补全，生成增强版 JSON，暴露在 `/v3/api-docs-enhanced`
4. **Swagger UI 渲染**：ui-starter 提供的增强版 Swagger UI 优先加载增强版数据源，不可用时自动降级为原始数据源
5. **用户交互**：用户在 Swagger UI 页面上点击 AI 按钮，触发更多文档生成功能

---

## 核心功能

### 1. AI 自动补全

- **批量补全**：一键补全整个 API 文档中所有缺失的 description
- **单接口补全**：针对单个接口、参数、字段进行精准补全
- **智能上下文**：基于接口名称、参数类型、所属分组等上下文生成描述
- **缓存机制**：相同上下文的补全结果缓存 24 小时，避免重复调用
- **标记追溯**：AI 生成内容带有 `x-ai-generated: true` 标记

### 2. 文档生成

| 文档类型 | 用途 | 目标读者 |
|----------|------|----------|
| 集成指南 | API 使用说明、认证方式、错误处理 | 开发者 |
| 产品说明书 | 业务功能描述、使用场景 | 产品经理 |
| 需求文档 | 需求分析、功能规格 | 业务人员 |
| 交付文档 | 部署说明、运维指南 | 运维人员 |
| 测试用例 | 测试场景、预期结果 | 测试人员 |

### 3. RAG 检索增强

- **知识库检索**：生成文档前从知识库检索相关文档片段
- **相似度过滤**：低于阈值的片段自动丢弃
- **动态标注**：高相关片段标记 ✅，中等相关标记 ⚠️
- **四种向量数据库**：Milvus、Qdrant、PgVector、Weaviate 可切换
- **降级策略**：向量数据库不可用时，跳过 RAG 直接生成

### 4. 多 LLM 支持

| 提供者 | 类型 | 说明 |
|--------|------|------|
| OpenAI | 云端 | GPT-4o 等模型 |
| DeepSeek | 云端 | deepseek-chat 等模型 |
| 阿里云百炼 | 云端 | 通义千问系列模型 |
| Ollama | 本地 | 本地部署的开源模型 |
| llama.cpp | 本地 | 本地部署的开源模型 |

### 5. 双模式运行

| 模式 | 配置值 | 适用场景 |
|------|--------|----------|
| **内嵌模式** | `embedded` | 个人开发、小型项目 |
| **客户端模式** | `client` | 团队共享 AI 服务 |

---

## 前端使用指南

### 顶部 AI 按钮栏

增强版 Swagger UI 在顶部区域新增 9 个 AI 操作按钮：

| 按钮 | 图标 | 功能 | 触发接口 |
|------|------|------|----------|
| 补全所有描述 | 🤖 | 对整个 API 文档所有缺失 description 的元素调用 AI 补全 | POST `/api/ai/complete-all` |
| 生成集成指南 | 📄 | 基于当前 OpenAPI 规范生成面向开发者的集成指南 | POST `/api/ai/generate-guide` |
| 生成产品说明书 | 📋 | 基于当前 OpenAPI 规范生成面向产品的功能说明 | POST `/api/ai/generate-spec` |
| 生成需求文档 | 📝 | 基于当前 OpenAPI 规范生成面向业务的需求文档 | POST `/api/ai/generate-requirement` |
| 生成交付文档 | 📦 | 基于当前 OpenAPI 规范生成面向运维的交付文档 | POST `/api/ai/generate-delivery` |
| 生成测试用例 | 🧪 | 基于当前 OpenAPI 规范生成面向测试的测试用例 | POST `/api/ai/generate-testcases` |
| RAG 设置 | 📊 | 打开 RAG 知识库配置面板 | - |
| AI 模型设置 | ⚙️ | 打开 AI 模型配置面板 | GET/POST `/api/ai/model-config` |
| 恢复原始文档 | ↩️ | 丢弃所有 AI 生成内容，重新加载原始 OpenAPI 规范 | 重新加载 `/v3/api-docs` |

### RAG 设置面板

RAG 设置面板包含 6 个标签页，分别对应不同文档类型：

| 标签页 | 对应 RAG 集合 | 配置项 |
|--------|---------------|--------|
| 产品文档 | `swagger_knowledge_product_doc` | 知识库路径、向量数据库选择、同步/索引/加载按钮 |
| 需求文档 | `swagger_knowledge_requirement_doc` | 同上 |
| 交付文档 | `swagger_knowledge_delivery_doc` | 同上 |
| 测试用例 | `swagger_knowledge_testcase_doc` | 同上 |
| 集成指南 | `swagger_knowledge_integration_guide` | 同上 |
| API 文档 | `swagger_knowledge_api` | 同上 |

**操作流程**：
1. 配置知识库路径（支持 `file:` 前缀的本地文件/目录，或 `classpath:` 前缀的资源文件）
2. 选择向量数据库（Milvus / Qdrant / PgVector / Weaviate）
3. 点击"同步知识库"：将本地文档分割、Embedding 后存入向量数据库
4. 点击"创建索引"：为向量集合创建 IVF_FLAT 索引（仅 Milvus）
5. 点击"加载到内存"：将集合加载到 Milvus 内存，提升检索性能

### AI 模型设置面板

配置项：
- **模型提供者**：OpenAI / DeepSeek / 阿里云百炼 / Ollama / llama.cpp
- **API Key**：通过环境变量注入（必填，本地模型除外）
- **Base URL**：API 端点地址（有默认值）
- **模型名称**：如 `gpt-4o`、`deepseek-chat`、`llama3:latest`
- **超时时间**：默认 120 秒，本地模型建议 300 秒
- **温度**：默认 0.3，越低输出越稳定
- **最大 Token 数**：默认 4096

**自动探测**：保存配置后自动探测模型的 `max_context_tokens` 并写入数据库，用于后续的 Token 估算。

### 文档弹窗

生成文档后会弹出文档查看弹窗，底部操作栏包含：

| 操作 | 功能 |
|------|------|
| 历史记录 | 查看最近 10 条生成记录（可配置） |
| 复制内容 | 一键复制全部 Markdown 内容 |
| 下载 | 下载为 `.md` 文件 |
| 格式选择 | 切换 Markdown 渲染模式或纯文本模式 |

### 文档润色开关

- **总开关**：控制是否启用 Refine 润色阶段
- **子开关**：控制润色时使用详细描述还是精简描述
- **联动逻辑**：总开关关闭时，子开关自动禁用

### 字段补全模式

请求体和响应体的字段补全支持两种模式：

| 模式 | 说明 |
|------|------|
| Example Value | 在示例值旁显示 AI 生成的字段描述 |
| Schema | 在 Schema 定义旁显示 AI 生成的字段描述 |

### 补全描述按钮

每个接口详情区域内有两种补全按钮：

| 按钮 | 位置 | 功能 |
|------|------|------|
| 🤖 补全描述 | 接口标题区域 | 补全整个接口的描述（包括操作、参数、响应） |
| 🤖 补全 | 每个参数/字段旁 | 仅补全单个参数或字段的描述 |

---

## RAG 部署指南

### 1. MySQL 数据库初始化

```bash
# 创建数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS swagger_rag CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 执行初始化脚本
mysql -u root -p swagger_rag < init.sql
```

### 2. Milvus 部署（WSL2 Ubuntu）

创建 `docker-compose.yml`：

```yaml
version: '3.8'
services:
  milvus:
    image: milvusdb/milvus:v2.6.18
    ports:
      - "19530:19530"
      - "9091:9091"
    environment:
      - MILVUS_MODE=standalone
      - ETCD_ENDPOINTS=etcd:2379
      - MINIO_ADDRESS=minio:9000
    volumes:
      - ./milvus-data:/var/lib/milvus
    networks:
      - milvus
  etcd:
    image: quay.io/coreos/etcd:v3.5.5
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000
      - ETCD_QUOTA_BACKEND_BYTES=4294967296
      - ETCD_SNAPSHOT_COUNT=50000
    volumes:
      - ./etcd-data:/etcd
    networks:
      - milvus
  minio:
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    command: minio server /minio_data --console-address ":9001"
    volumes:
      - ./minio-data:/minio_data
    networks:
      - milvus
networks:
  milvus:
    driver: bridge
```

启动 Milvus：

```bash
docker-compose up -d
```

验证连接：

```bash
curl http://localhost:9091/api/v1/health
```

### 3. Ollama 安装及模型拉取（WSL2 Ubuntu）

安装 Ollama：

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

启动 Ollama 服务：

```bash
ollama serve
```

拉取模型：

```bash
# 对话模型（用于生成文档）
ollama pull llama3:latest

# Embedding 模型（用于 RAG 检索）
ollama pull nomic-embed-text:latest
```

验证模型可用：

```bash
ollama list
```

### 4. Embedding 维度探测

系统会自动探测 Embedding 模型的维度：

1. 首次同步知识库时，系统会调用一次 Embedding API 获取向量维度
2. 维度信息保存到数据库，用于创建向量索引
3. 如果探测失败，使用默认值 -1（表示未配置），首次同步时系统会自动探测 Embedding 模型的实际维度（nomic-embed-text 为 768）

### 5. 前端 RAG 设置面板操作

完成以上部署后，在前端 RAG 设置面板中：

1. 选择向量数据库为 Milvus
2. 配置 Milvus 连接信息（host: localhost, port: 19530）
3. 设置知识库路径（如 `file:/path/to/your/documents/`）
4. 点击"同步知识库"→"创建索引"→"加载到内存"
5. 验证检索效果：生成文档时会显示"📊 知识库参考情况"

---

## 快速开始

### 环境要求

- **JDK 17+**
- **Maven 3.9+**
- **MySQL 8.0.46**（推荐，仅 RAG 启用时需要）
- **Ollama 0.6.8+**（本地模型推荐，部署于 WSL2 Ubuntu）
- **Milvus Standalone v2.6.18**（Docker Compose 部署于 WSL2 Ubuntu）
- **Docker + Docker Compose**
- **WSL2 Ubuntu**（Windows 用户推荐）

### 获取项目

```bash
git clone <repository-url>
cd swagger-ai-enhancer
```

### 启动 Demo

1. **配置 AI 连接信息**（修改 `swagger-ai-enhancer-demo/src/main/resources/application.yml`）：

```yaml
swagger-ai-enhancer:
  ai:
    mode: embedded
    llm:
      provider: ollama  # 或 deepseek/openai/aliyun
      ollama:
        base-url: http://localhost:11434
        model: llama3:latest
```

2. **启动 Demo 应用**：

```bash
cd swagger-ai-enhancer-demo
mvn spring-boot:run
```

3. **访问 Swagger UI**：

打开浏览器访问 `http://localhost:8080/swagger-ui.html`

---

## 部署方案

### 1. 开发环境部署

在项目的 `pom.xml` 中引入依赖：

```xml
<!-- 方式一：一键引入全部 -->
<dependency>
    <groupId>com.swagger.ai</groupId>
    <artifactId>swagger-ai-enhancer-all-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- 方式二：按需引入 -->
<dependency>
    <groupId>com.swagger.ai</groupId>
    <artifactId>swagger-ai-enhancer-springdoc-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

配置 `application.yml`：

```yaml
swagger-ai-enhancer:
  enabled: true
  springdoc:
    enhance-enabled: true
  ai:
    mode: embedded
    llm:
      provider: deepseek
      deepseek:
        api-key: ${DEEPSEEK_API_KEY}
```

### 2. AI 服务独立部署

构建 AI 服务 JAR：

```bash
cd swagger-ai-enhancer-ai-starter
mvn clean package
```

启动：

```bash
java -jar target/swagger-ai-enhancer-ai-starter-1.0.0-SNAPSHOT.jar --server.port=8081
```

其他项目使用客户端模式连接：

```yaml
swagger-ai-enhancer:
  ai:
    mode: client
    service-url: http://ai-service.your-company.com:8081
```

### 3. Docker Compose 一键部署

适用于完整演示环境，同时启动 AI 服务和增强版 UI：

```yaml
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
```

---

## 配置说明

### 全局配置

```yaml
swagger-ai-enhancer:
  enabled: true                    # 全局开关
  springdoc:
    enhance-enabled: true          # 是否启用 AI 增强管道
    enhanced-endpoint: /v3/api-docs-enhanced
    ai-generated-marker: true
    marker-field: x-ai-generated
    ai-service-url: http://localhost:8081  # AI 服务地址（客户端模式）
  ai:
    mode: embedded                 # embedded / client
    service-url: http://localhost:8081  # client 模式下的远程地址
    llm:
      provider: deepseek           # openai / deepseek / aliyun / ollama / llamacpp
      timeout-seconds: 120         # LLM 调用超时时间，本地模型建议 300
      max-tokens: 4096
      temperature: 0.3
      # 各提供者的具体配置（api-key、base-url、model 等）
    rag:
      enabled: true
      vector-store: milvus         # milvus / qdrant / pgvector / weaviate
      top-k: 5
      similarity-threshold: 0.7
      embedding-model: nomic-embed-text:latest  # Embedding 模型名称
      collection-prefix: swagger_knowledge      # 向量集合前缀
      knowledge-paths:             # 按 docType 配置知识库路径
        product-doc: file:/path/to/product-docs/
        requirement-doc: file:/path/to/requirements/
        delivery-doc: file:/path/to/delivery/
        testcase-doc: file:/path/to/testcases/
        integration-guide: file:/path/to/guides/
        api: file:/path/to/api-docs/
      # 向量数据库连接信息
```

### 各 LLM 提供者配置示例

**DeepSeek**：
```yaml
swagger-ai-enhancer:
  ai:
    llm:
      provider: deepseek
      deepseek:
        api-key: ${DEEPSEEK_API_KEY}
        base-url: https://api.deepseek.com/v1
        model: deepseek-chat
```

**Ollama**：
```yaml
swagger-ai-enhancer:
  ai:
    llm:
      provider: ollama
      ollama:
        base-url: http://localhost:11434
        model: llama3:latest
```

**OpenAI**：
```yaml
swagger-ai-enhancer:
  ai:
    llm:
      provider: openai
      openai:
        api-key: ${OPENAI_API_KEY}
        base-url: https://api.openai.com/v1
        model: gpt-4o
```

### RAG 向量数据库配置

**Milvus**：
```yaml
swagger-ai-enhancer:
  ai:
    rag:
      vector-store: milvus
      milvus:
        host: localhost
        port: 19530
        collection-name: swagger_knowledge
```

**PgVector**：
```yaml
swagger-ai-enhancer:
  ai:
    rag:
      vector-store: pgvector
      pgvector:
        host: localhost
        port: 5432
        database: swagger_ai
        username: ${POSTGRES_USER}
        password: ${POSTGRES_PASSWORD}
```

---

## API 接口列表

### AI 文档生成接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ai/complete-one` | POST | 补全单个接口/字段的描述 |
| `/api/ai/complete-all` | POST | 批量补全整个 OpenAPI JSON 中所有缺失描述 |
| `/api/ai/generate-guide` | POST | 生成集成指南（Markdown） |
| `/api/ai/generate-spec` | POST | 生成产品说明书（Markdown） |
| `/api/ai/generate-requirement` | POST | 生成需求文档（Markdown） |
| `/api/ai/generate-delivery` | POST | 生成交付文档（Markdown） |
| `/api/ai/generate-testcases` | POST | 生成测试用例（Markdown） |
| `/api/ai/health` | GET | 健康检查 |

### RAG 管理接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ai/rag/sync` | POST | 同步知识库到向量数据库 |
| `/api/ai/rag/index` | POST | 创建索引 |
| `/api/ai/rag/load` | POST | 加载集合 |
| `/api/ai/rag/release` | POST | 释放集合 |
| `/api/ai/rag/collections` | GET | 获取所有集合名称 |
| `/api/ai/rag/config` | GET | 获取 RAG 配置 |
| `/api/ai/rag/collection-status` | GET | 获取集合状态 |
| `/api/ai/rag/test-connection` | POST | 测试向量数据库连接 |

### AI 模型配置接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ai/model-config` | GET | 获取模型配置列表 |
| `/api/ai/model-config` | POST | 保存/更新模型配置 |
| `/api/ai/model-config/test-connection` | POST | 测试模型连接 |

### AI 设置接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ai/settings` | GET | 获取所有设置 |
| `/api/ai/settings/{docType}` | GET | 获取指定文档类型的设置 |

### springdoc 增强端点

| 接口 | 方法 | 说明 |
|------|------|------|
| `/v3/api-docs` | GET | 原版 OpenAPI JSON（不受影响） |
| `/v3/api-docs-enhanced` | GET | AI 增强后的 OpenAPI JSON |

---

## Demo 示例接口

项目包含 `swagger-ai-enhancer-demo` 模块，提供 UserController 示例接口，用于验证 AI 补全功能：

| 接口路径 | 方法 | 说明 |
|----------|------|------|
| `/api/users/{id}` | GET | 获取用户详情 |
| `/api/users` | POST | 创建用户 |
| `/api/users/{id}` | PUT | 更新用户 |
| `/api/users/{id}` | DELETE | 删除用户 |
| `/api/users` | GET | 获取用户列表 |

> **注意**：这些接口故意不写 `description` 和 `summary`，用于验证 AI 自动补全功能的效果。

---

## 注意事项

### 已知限制

1. **内部路径过滤**：已过滤 `/api/ai/*`、`/v3/api-docs-enhanced` 等内部路径，但如果新增内部 Controller，需要同步更新 `isInternalPath` 方法中的前缀列表
2. **Token 估算**：采用 `json.length() / 2` 的粗略估算方法，实际 Token 数可能因字符编码和模型差异而有所偏差
3. **RAG 性能**：向量数据库连接失败时会自动降级，但首次检索可能较慢（取决于网络延迟）
4. **AI 输出质量**：生成内容的质量取决于所选模型和 Prompt 设计，建议使用高质量模型（如 GPT-4o、DeepSeek-V3）

### 后续改进方向

1. 支持更多 LLM 提供者（如 Claude、Gemini）
2. 支持更多向量数据库（如 Pinecone、Chroma）
3. 实现实时文档同步（监听文件变化自动更新向量索引）
4. 添加文档版本管理
5. 支持多语言文档生成
6. 实现 AI 生成内容的审核机制

### 安全注意事项

1. **API Key**：必须通过环境变量注入，不要硬编码或提交到代码仓库
2. **输入验证**：所有外部输入（OpenAPI JSON、用户查询）都经过校验和清洗
3. **输出转义**：AI 生成的文本渲染到 HTML 时进行转义，防止 XSS
4. **CORS**：仅允许配置的来源访问 API，默认不允许跨域

---

## 开源协议

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 贡献指南

欢迎提交 Issue 和 Pull Request！

### 开发流程

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/your-feature`)
3. 提交代码（遵循 Conventional Commits 规范）
4. 推送到分支 (`git push origin feature/your-feature`)
5. 创建 Pull Request

### 提交规范

遵循 Conventional Commits 规范，格式如下：

```
<type>(<scope>): <description>

<body>

<footer>
```

**类型（type）**：
- `feat`: 新增功能
- `fix`: 修复 Bug
- `docs`: 文档更新
- `style`: 代码格式（不影响逻辑）
- `refactor`: 重构（不新增功能也不修复 Bug）
- `perf`: 性能优化
- `test`: 测试相关
- `chore`: 构建/工具/依赖更新

**示例**：
- `feat(ai): add RAG integration support`
- `fix(ui): fix document modal layout issue`
- `docs(readme): update deployment guide`

### 代码规范

- Java：遵循项目根目录的 `formatter.xml`，2 空格缩进
- 使用 Lombok（@Data, @Builder, @Slf4j）
- 新增组件支持暗色模式
- 前端使用 ESLint + Prettier

### 测试要求

- 单元测试覆盖率 ≥ 70%
- 使用 JUnit 5 + Mockito
- AI 相关测试必须使用 Mock，不实际调用外部 LLM

---

## 更新日志

### v1.0.0-SNAPSHOT

- 基础功能：AI 自动补全、文档生成、RAG 检索
- 支持 5 种 LLM 提供者：OpenAI、DeepSeek、阿里云百炼、Ollama、llama.cpp
- 支持 4 种向量数据库：Milvus、Qdrant、PgVector、Weaviate
- 支持 6 种文档类型：集成指南、产品说明书、需求文档、交付文档、测试用例、API 补全
- 超大型项目分组生成方案
- Swagger UI 插件式改造，不修改原版核心文件
- Token 溢出保护和自动降级策略
- 内嵌模式和客户端模式双模式运行
- 完善的缓存机制（24 小时 TTL）