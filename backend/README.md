# AI Code Helper Backend

Java 21、Spring Boot 3.5 和 LangChain4j 后端，提供普通聊天、SSE 流式回复、会话记忆、结构化学习报告、本地知识库 RAG、自定义工具、可选 MCP、输入 Guardrail 与访客身份验证。

默认不需要任何外部密钥：没有 `DASHSCOPE_API_KEY` 时会自动使用本地模拟 Chat/Streaming 模型与确定性 Embedding 模型，因此可以离线启动、联调和运行全部测试。配置密钥后，同一套 LangChain4j AI Services 会切换到通义千问。

## 技术版本

- Java 21+
- Maven 3.6+
- Spring Boot 3.5.16
- LangChain4j core/BOM 1.17.2
- LangChain4j community DashScope/MCP 1.17.2-beta27
- Jsoup 1.21.2

Maven Enforcer 会在构建开始时检查 Java 和 Maven 版本。

## 快速启动

```bash
cd backend
mvn spring-boot:run
```

服务默认监听 `http://localhost:8081`。验证：

```bash
curl http://localhost:8081/api/health
```

如需明确强制离线模式：

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

## 使用通义千问

所有密钥都来自环境变量，不应写入 `application.yml` 或提交到版本库。

```bash
export DASHSCOPE_API_KEY='your-chat-key'
# 可选；未设置时 Embedding 会复用聊天 key
export DASHSCOPE_EMBEDDING_API_KEY='your-embedding-key'
export APP_AUTH_TOKEN_SECRET="$(openssl rand -hex 32)"
mvn spring-boot:run
```

默认模型为 `qwen-max` 和 `text-embedding-v4`，可通过 `DASHSCOPE_CHAT_MODEL`、`DASHSCOPE_STREAMING_CHAT_MODEL` 与 `DASHSCOPE_EMBEDDING_MODEL` 修改。项目同时兼容 starter 标准的 `langchain4j.community.dashscope.*` 属性。为保证空 key 可离线启动并统一安全监听器，starter 自动建模被关闭，由 `ModelConfiguration` 根据两套属性手动创建 `QwenChatModel`、`QwenStreamingChatModel` 和 `QwenEmbeddingModel`。

## 推荐的流式聊天协议

浏览器 `EventSource` 不能设置自定义请求头，而且长 prompt 不应放入 URL。主协议因此采用一次性短期票据：

1. `POST /api/ai/chat/streams` 以 JSON 安全提交消息；服务端完成身份绑定与 Guardrail 校验，返回 30 秒有效的一次性 `streamId`。
2. `GET /api/ai/chat/streams/{streamId}` 打开 SSE；票据只能由创建它的同一 Cookie 身份原子消费一次。

先创建或续期访客。有效 Cookie 存在时，此接口会返回同一个 `userId`：

```bash
curl -c cookies.txt -b cookies.txt \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"小明"}' \
  http://localhost:8081/api/users/guest
```

创建流票据：

```bash
curl -c cookies.txt -b cookies.txt \
  -H 'Content-Type: application/json' \
  -d '{"memoryId":"demo-1","message":"如何学习 Spring Boot？"}' \
  http://localhost:8081/api/ai/chat/streams
```

随后访问响应中的 `streamUrl`：

```bash
curl -N -b cookies.txt \
  http://localhost:8081/api/ai/chat/streams/<streamId>
```

SSE 事件契约：

- `meta`：模型与 `memoryId`。
- `message`：增量文本片段，可出现多次。
- `done`：成功结束，数据固定为 `[DONE]`。
- `error`：安全错误码，随后连接关闭。

浏览器端应使用 Cookie：

```js
const source = new EventSource(streamUrl, { withCredentials: true })
source.addEventListener('message', event => appendChunk(event.data))
source.addEventListener('done', () => source.close())
source.addEventListener('error', () => source.close())
```

`GET /api/ai/chat?memoryId=...&message=...` 仅为旧客户端兼容，限制为 1000 字符并返回弃用响应头；浏览器的跨站触发会被拒绝。该接口仍会让 prompt 出现在 URL，因此新代码不要使用它。

### 重新生成

票据请求可添加 `"regenerate": true`。成功消费票据后，服务端会原子移除同一身份、同一 `memoryId` 的最近一轮 User/Assistant 消息，再生成新回复；仅创建但未消费的票据不会改动记忆。正常流与重新生成流都会先保存记忆快照，只有成功发送 `done` 才提交新记忆；模型错误、发送失败或浏览器断连会恢复旧快照并释放并发额度。

## REST API

### 普通聊天

```http
POST /api/ai/chat
Content-Type: application/json

{
  "memoryId": "demo-1",
  "message": "给我一个 Java 21 学习计划"
}
```

`userId` 是可选兼容字段。通常无需发送，因为后端会直接从签名 HttpOnly Cookie 解析访客身份；如果显式发送，必须与 Cookie 中的用户一致。

### 结构化报告

```http
POST /api/ai/report
```

请求体与普通聊天相同。`ReportAssistant` 的返回类型是 `LearningReportDraft`，LangChain4j 会向模型追加格式约束并直接解析为 Java 结构化对象；服务端严格校验 3–6 个目标、四个不重复周次、2–4 个项目和 4–8 项面试动作后才返回。离线模型也会根据 Java、前端、Python/数据等不同方向生成可解析且内容不同的 JSON。

### RAG

```http
POST /api/ai/rag
```

响应中的 `sources` 包含知识文档标题、文件名、摘录和相似度分数。本地 Markdown 示例位于 `src/main/resources/knowledge-base/`。添加文档后重启应用即可；知识库在第一次 RAG 请求时惰性向量化，启动阶段不访问外部网络。

### 用户验证

- `POST /api/users/guest`：创建或续期访客并设置签名 HttpOnly Cookie。
- `GET /api/users/verify?userId=<uuid>`：检查 Cookie 是否匹配该用户。
- `POST /api/users/verify`：请求体为 `{ "userId": "<uuid>" }`。

没有访客 Cookie 时仍允许匿名聊天；服务端会下发独立的签名匿名 Cookie。会话键始终由“签名身份 + memoryId”组合，不同客户端使用相同 `memoryId` 也不会共享记忆。

## RAG、工具和 MCP

AI Services 注册了 `InterviewQuestionTool`。只有模型主动选择工具时，Jsoup 才访问配置的可信面试题搜索地址；用户不能传入任意 URL。

BigModel Web Search MCP 默认关闭且不会在启动时联网。启用方式：

```bash
export BIGMODEL_MCP_ENABLED=true
export BIGMODEL_API_KEY='your-key'
```

实现使用新版 `StreamableHttpMcpTransport`，通过 `Authorization: Bearer ...` 请求头连接 `web_search_prime/mcp`，并默认只授权 `webSearchPrime` 与 `web_search_prime` 两个工具名。初始化发生在首次需要动态工具的聊天请求，而不是应用启动时；MCP 故障不会阻塞启动，失败后默认退避 30 秒再尝试。

## 安全设计

- 输入在控制层和 LangChain4j AI Services 内各经过一次同一 Spring `SafeInputGuardrail` 实例。
- 访客 token 使用 HMAC-SHA256 签名，显示名保存在签名 token 中，服务端不维护无界用户缓存。
- 显式配置的 `APP_AUTH_TOKEN_SECRET` 少于 32 个 UTF-8 字节时应用会拒绝启动；无配置的本地模式使用随机 32 字节密钥。
- Cookie 默认 `HttpOnly`、`SameSite=Lax`；HTTPS 部署应设置 `APP_SECURE_COOKIES=true`。跨站部署若使用 `SameSite=None`，也必须启用 Secure。
- CORS 默认只允许 `localhost:5173` 与 `127.0.0.1:5173`，并允许 credentials；生产环境用 `APP_CORS_ALLOWED_ORIGINS` 指定准确来源，不能使用 `*`。
- 响应包含 nosniff、DENY frame、CSP、Referrer-Policy、Permissions-Policy 和 no-store headers。
- 请求日志只记录方法、路径、状态、耗时、消息字符数与不可逆的会话指纹，不记录 query、完整 prompt、Cookie、API key 或模型响应。
- MCP 日志关闭，认证 key 只放在请求头；Actuator 只暴露 health/info。
- AI 调用具有同会话互斥、全局与单用户并发/每分钟启动上限；待消费票据也有全局及单用户容量限制。
- 会话容量满时只驱逐非活跃 LRU；驱逐会先同步清理 Core/RAG 两个 LangChain4j `ChatMemoryAccess` 缓存，再删除注册表对象，避免首次失败或缓存驱逐造成记忆分叉。
- 过期 SSE 票据除请求时惰性清理外，也会每 30 秒主动删除，其中的原始 prompt 不会无限驻留。

生产环境还应把内存会话存储和向量存储迁移到有容量、TTL、备份和访问控制的持久化服务，并在网关层加入 TLS、限流和请求大小限制。当前 Guardrail 是输入侧的启发式规则，不替代业务内容审核；MCP 也应在网关侧配置熔断/退避。应用会取消并忽略已终止流的后续回调，但所用 DashScope SDK 版本不保证 `StreamingHandle.cancel()` 一定关闭已经发出的上游 HTTP 请求，因此生产环境还需设置供应商侧超时、配额和成本告警。

## 主要配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | HTTP 端口 |
| `DASHSCOPE_API_KEY` | 空 | 通义千问 Chat key；为空时普通聊天走本地模型 |
| `DASHSCOPE_STREAMING_API_KEY` | 空 | 可选的独立 Streaming key；为空时复用 Chat key |
| `DASHSCOPE_EMBEDDING_API_KEY` | 空 | Embedding key；为空时复用聊天 key |
| `APP_AUTH_TOKEN_SECRET` | 启动时随机 | 签名密钥；配置时至少 32 字节，非本地环境必须固定设置 |
| `APP_CORS_ALLOWED_ORIGINS` | 两个本地 Vite 来源 | 允许携带 Cookie 的精确来源列表 |
| `AI_MAX_MEMORY_MESSAGES` | `20` | 每个会话保留的消息窗口 |
| `AI_MAX_CONVERSATIONS` | `2000` | 进程内最多保留的会话；满时协调驱逐非活跃 LRU |
| `AI_STREAM_TICKET_TTL` | `30s` | 一次性 SSE 票据有效期 |
| `RAG_LOCATION` | `classpath*:knowledge-base/*.md` | 知识文档资源模式 |
| `BIGMODEL_MCP_ENABLED` | `false` | 是否启用 MCP |
| `BIGMODEL_API_KEY` | 空 | BigModel MCP key |

其余变量见 `.env.example` 与 `application.yml`。

## 测试与打包

```bash
mvn test
mvn package
```

测试完全离线，覆盖 Guardrail、签名与过期、身份隔离记忆、首次失败缓存一致性、协调 LRU 驱逐、最近一轮回退与失败流回滚、本地 Embedding、标准 starter 属性、Jsoup 解析、RAG 来源、按方向变化的结构化报告、并发边界、SSE 票据单次消费以及 `done/[DONE]` 契约。当前基线在 Temurin Java 21.0.11 下为 35 项测试全绿。

打包产物：

```bash
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

## 代码分层

```text
HTTP Controller
  -> 身份绑定 / Guardrail / SSE ticket
  -> AiChatService
  -> LangChain4j AI Services
       -> Chat + Streaming Model (Qwen 或 local)
       -> MessageWindowChatMemory
       -> KnowledgeBaseRetriever + InMemoryEmbeddingStore
       -> Jsoup Tool / optional MCP ToolProvider
```
