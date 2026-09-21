# AI Code Helper project operations

This authored reference documents code-backed baseline behavior, not claims about measured production performance. Source inspection covers the current repository implementations listed below. Configuration values may be overridden; distinguish defaults from deployment facts. 这份文档描述代码可核实的行为，不提供真实模型质量、线上吞吐量或费用测量结论。

## Guest identity and memory isolation

The conversation key combines a verified signed owner identity with memoryId. ClientIdentityService constructs guest:<userId>:memory:<memoryId> for authenticated guests and anonymous:<userId>:memory:<memoryId> for signed anonymous sessions. A supplied userId must match the verified token; knowledge of another UUID does not grant access. The same memoryId under two owners is therefore a different memory key. Memory IDs name conversations inside an identity boundary, not global shared chat rooms.

会话键由经过验证的签名身份与 memoryId 共同组成。请求显式传入 userId 时，它必须与已验证的访客令牌一致；知道他人的 UUID 不能获得其会话访问权限。两个用户可以使用相同 memoryId，因为 owner 不同。不要把客户端传入的 memoryId 单独作为鉴权依据。

## SSE ticket lifecycle

A random SSE ticket binds the submitted message to its conversation identity and is consumed only once. POST /api/ai/chat/streams accepts the chat body and returns a random streamId, a stream URL and expiresAt. GET /api/ai/chat/streams/{streamId} validates the caller identity and consumes that ticket atomically. The default ticket lifetime is 30 seconds, configurable through AI_STREAM_TICKET_TTL. Expired or already consumed tickets cannot open another stream; a wrong owner's claim does not consume a still-valid ticket. This keeps message bodies and access tokens out of EventSource query strings.

SSE 使用先 POST 后 GET 的票据协议：消息正文在请求体中提交，服务器返回随机 streamId、流地址和过期时间。票据绑定会话身份，默认 30 秒有效，只能消费一次。持有票据 UUID 不等于获得访问权限。正文与访问令牌无需出现在 URL 查询参数中；错误身份尝试也不会抢走合法用户尚未消费的票据。

## Local demo model boundary

Passing local-mock tests does not establish real Qwen answer quality or external tool reliability. Without a DashScope key the application selects local model substitutes and local hash embeddings. They make API, streaming, identity, rollback and persistence checks reproducible without paid model calls. The local text generator uses deterministic templates and is not a semantic substitute for Qwen. A successful local response verifies application wiring and contracts, not factual accuracy, natural-language reasoning, real-provider latency or external MCP success. Those claims need separate explicitly labeled provider evaluation.

未提供 DashScope 密钥时，项目使用本地模型替身和哈希 embedding。离线测试可以验证接口、流式协议、身份隔离、回滚与持久化；不能证明真实 Qwen 的回答质量、推理效果、响应时间或 MCP 调用可靠性。本地模板输出与真实生成模型必须分开记录，真实服务指标需要独立实验。

## Conversation durability and signing key

APP_DATA_DIR stores completed conversation snapshots and the generated signing key by default. The default private data directory is ./data and storage is enabled unless APP_STORAGE_ENABLED=false. A persistent volume for APP_DATA_DIR retains committed conversation snapshots and the automatically generated signing key across process or container restarts. APP_AUTH_TOKEN_SECRET overrides the stored key; intentional key rotation invalidates tokens signed by the old key. Persistence does not extend token expiration: APP_GUEST_TTL still defaults to 12 hours. Protect snapshots because they contain conversation text.

默认 APP_DATA_DIR 为 ./data，保存已完成会话快照和自动生成的签名密钥。容器重启后要继续保留这些数据，需要把该目录挂载到持久卷。APP_AUTH_TOKEN_SECRET 可以覆盖磁盘密钥，轮换密钥会使旧签名令牌失效。持久化不会取消令牌过期规则，APP_GUEST_TTL 默认仍为 12 小时。快照含会话正文，应限制访问权限。

## Failed-turn memory rollback

Regeneration takes a rewind snapshot before generation and restores it if the turn fails. The controller acquires a conversation execution lease before modifying memory. Normal turns snapshot the current conversation; regeneration uses rewindLastTurnWithSnapshot before asking the model again. A successful turn commits memory to storage. If synchronous generation throws or the stream fails or is cancelled, the lifecycle restores the snapshot rather than saving a partial result. This preserves the earlier completed exchange after failed regeneration. A retry is still a new model invocation and can have a provider cost.

生成前先取得会话执行租约并保存快照；重新生成通过 rewindLastTurnWithSnapshot 撤回上一轮，同时保留可恢复的原始状态。只有成功完成才提交持久化。异常、流失败或取消时恢复快照，避免半截回复污染历史，也避免失败的重新生成丢失上一轮完整回答。重试仍可能产生新的上游调用费用。

## Conversation admission controls

AiExecutionRegistry enforces conversation mutual exclusion separately from concurrency and per-minute start limits. An active conversation cannot acquire another lease. Global and owner-scoped active counters limit simultaneous model requests, while separate global and owner-scoped counters limit request starts within a fixed epoch-minute window. The baseline defaults are 64 active requests globally, 2 per owner, 300 starts per minute globally and 20 per owner; environment variables can override them. Closing a lease releases active capacity once, but does not refund its counted start. These are process-local controls, so multiple replicas require shared admission coordination.

同一会话互斥、并发数和启动频率是三种不同约束。活跃租约计数限制同时进行的请求；启动计数按固定分钟窗口限制新请求数量。基线默认全局并发 64、每用户并发 2，全局每分钟启动 300、每用户 20，均可配置。关闭租约只释放活跃并发容量，不退还本分钟已经发生的启动次数。这些计数在进程内，多副本不能把它当全局限流。

## MCP optional connection and allowlist

Disabled or unconfigured MCP exposes no tools and makes no startup connection. OptionalMcpToolProvider requires the enabled flag, an API key and an endpoint. When configured, it connects lazily on the first tool-enabled request instead of eagerly during application startup. Tool discovery is filtered to configured allowed names. The transport uses a Bearer Authorization header, initialization and execution timeouts, and failure backoff. Configuration means a provider may be attempted; it is not evidence that remote tools are connected, available or successful. Offline tests should report MCP disabled rather than claim a live search succeeded.

MCP 关闭或缺少必要配置时不会暴露工具，也不会在启动阶段连接 BigModel。启用、密钥和 endpoint 齐全后，首次需要工具的请求才触发惰性连接。工具名称受允许列表限制；通信使用 Bearer 认证，并设置超时与失败退避。已配置不等于连接成功，更不等于检索成功，离线验证应明确标注 MCP 未执行。

## SSE wire completion and whitespace

SSE message events carry JSON content, and the done event uses the [DONE] sentinel. Incremental text is serialized in a JSON content field on message events so whitespace, newlines and code indentation survive event framing. The stream also supports sources metadata. The frontend treats done/[DONE] as successful completion and closes its EventSource; an error or an unexplained connection close is not the same success signal. This distinction matters because truncated answers must not appear as completed model turns. Native EventSource automatic reconnection is inappropriate for consuming a single-use ticket a second time.

message 事件中的增量文本使用 JSON content 字段，避免 SSE 分行协议吞掉代码空格和缩进。sources 事件携带来源元数据；done 事件与 [DONE] 标记表示正常完成，前端随后关闭 EventSource。错误或没有完成标记的断开不应当作完整成功；票据只能用一次，不能把自动重连当成重新生成。

## Single-instance file-store boundary

The local snapshot file store supports one backend instance and is not a shared transactional database. LocalStateStore writes snapshots atomically and applies owner-only file permissions on POSIX systems where supported. Atomic replacement prevents a reader from observing a partly written single file; it does not serialize competing application replicas or provide cross-process transactions. A multi-instance production design needs a shared transactional store, consistent identity secrets and explicit retention, backup and access-control policies. Mounting the same directory into multiple replicas alone does not supply the missing coordination.

默认本地快照存储支持单个后端实例，原子文件替换不等于跨实例事务。POSIX 下限制文件权限可以保护本机数据，但把同一目录挂载到多个副本并不能解决并发写入、会话版本或分布式互斥。多实例应使用共享事务存储、稳定一致的签名密钥，并定义保留期、备份和访问控制策略。

## Memory-capacity eviction

At memory capacity, the least recently used inactive conversation is evicted from both AI-service caches and the registry. ConversationMemoryManager prepares a stable memory object before either assistant can cache it. At capacity it selects an inactive least-recently-used candidate and rechecks inactivity under the execution registry monitor. It evicts the candidate from CoreAssistant and RagAssistant caches before removing the registry entry. Removing only the registry would leave stale assistant cache references and could break isolation or capacity accounting. If every candidate is active, admission is rejected rather than evicting in-flight memory.

达到容量时，优先选择最久未使用且不活跃的会话，并在执行注册表的同步保护下再次确认它未被使用。先从 CoreAssistant 和 RagAssistant 两个缓存中淘汰，再从应用注册表移除；只删注册表会留下旧引用。全部会话都活跃时拒绝新增，不能破坏正在生成的会话状态。

## Structured learning-report contract

The learning report contains a title, summary, goals, a four-week plan, recommended projects and an interview checklist. POST /api/ai/report requests a structured LearningReport object. The expected fields are title, summary, goals, weeklyPlan, recommendedProjects and interviewChecklist; each weekly plan item identifies a week, focus and tasks. The offline implementation creates deterministic direction-specific reports, while the model-backed service parses a generated structured result. A JSON-shaped answer must still pass object validation; valid syntax alone cannot establish that learning advice is appropriate or that every proposed project is feasible.

学习报告接口返回结构化对象而不是普通聊天段落，包括标题、摘要、目标、四周 weeklyPlan、推荐项目和面试检查清单。每周包含周次、重点与任务。离线模式给出按方向生成的模板报告，真实模型通过结构化解析得到对象。JSON 能解析不代表建议质量合格，也不代表计划和项目可行性已经验证。

## Cancellation and upstream requests

Local stream cancellation does not guarantee termination of an already dispatched DashScope HTTP request. The stream lifecycle stops processing callbacks, restores uncommitted memory and releases the local execution lease when cancelled. The cancellation handle exposed by the current provider SDK cannot be treated as proof that an already sent remote HTTP request stopped running. Consequently local active-request counts and provider billed work are different measurements. Provider-side deadlines, budgets and accounting are still needed for a cost claim. A cancelled UI must not display success merely because local cleanup finished.

点击停止后，本地会抑制回调、恢复尚未提交的记忆并释放执行租约，但不能据此保证已经发往 DashScope 的 HTTP 请求被终止，更不能保证不计费。本地活跃数与上游计费工作量是不同指标。成本控制仍需要服务提供方超时、预算和账单数据；本地清理完成也不等于一次成功回复。

## Guest cookie storage and expiration

The default guest credential is an HttpOnly cookie with a configurable expiration and SameSite policy. ClientIdentityService writes the guest cookie with HttpOnly, path /, a max-age based on APP_GUEST_TTL and configurable Secure and SameSite attributes. The default guest TTL is 12 hours. HttpOnly prevents ordinary page JavaScript from reading that cookie, reducing direct token theft via script access, but it does not make a page immune to XSS or solve every CSRF scenario. The frontend sends credentials with the request instead of placing the token in a stream URL. Production HTTPS requires appropriate secure-cookie settings.

默认访客凭证通过 HttpOnly Cookie 保存，页面 JavaScript 不能直接读取该 Cookie。有效期由 APP_GUEST_TTL 控制，默认 12 小时；Secure 与 SameSite 可以配置。HttpOnly 降低脚本直接窃取凭证的风险，但不能替代 XSS 与 CSRF 防护。请求携带 Cookie，不应把令牌放入 SSE URL，HTTPS 部署需正确配置安全 Cookie。

## Repository evidence

- `backend/src/main/java/com/aicodehelper/user/ClientIdentityService.java`
- `backend/src/main/java/com/aicodehelper/user/GuestSessionService.java`
- `backend/src/main/java/com/aicodehelper/ai/ChatStreamTicketService.java`
- `backend/src/main/java/com/aicodehelper/ai/AiExecutionRegistry.java`
- `backend/src/main/java/com/aicodehelper/ai/api/AiChatController.java`
- `backend/src/main/java/com/aicodehelper/memory/ConversationMemoryManager.java`
- `backend/src/main/java/com/aicodehelper/storage/LocalStateStore.java`
- `backend/src/main/java/com/aicodehelper/mcp/OptionalMcpToolProvider.java`
- `backend/src/main/java/com/aicodehelper/ai/local/LocalAnswerGenerator.java`
- `backend/src/main/resources/application.yml`

Protocol references: [WHATWG server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html), [RFC 6265 HTTP State Management](https://www.rfc-editor.org/rfc/rfc6265).
