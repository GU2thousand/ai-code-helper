# API foundations / 接口基础

Scope: HTTP API contracts, safe recovery, streaming, and compatibility. Examples illustrate protocol choices; deployment-specific limits and authentication policies require explicit configuration.

## Safe and idempotent HTTP methods

Idempotence concerns the intended server effect of repeated requests, not identical response bodies or status codes. A safe method asks for read-only semantics from the client perspective; operational side effects such as access logging can still occur. GET and HEAD are safe. PUT and DELETE are idempotent by their defined semantics, but are not safe. After a successful DELETE, a repeat may report that the resource is absent; the intended resource state can still be unchanged by repeating deletion. POST has no general idempotence guarantee, although an application can implement a specific deduplication contract. Method choice alone cannot repair an endpoint whose implementation violates its advertised semantics. Avoid state-changing actions behind GET because browsers, crawlers, caches, and prefetchers may request links without user intent to mutate data. For interview answers, distinguish client intent, resulting state, and response representation instead of defining idempotence as returning exactly the same JSON.

中文：安全方法表达客户端只读意图，访问日志等运行副作用仍可存在。GET 和 HEAD 是安全方法；PUT 与 DELETE 具有幂等语义，但不是安全方法。重复 DELETE 可以返回不同状态码，只要重复操作不会进一步改变预期资源状态。POST 没有通用幂等保证，可以由应用设计去重协议。不要把修改操作隐藏在 GET 中，因为预取、爬虫和缓存可能主动访问。幂等应围绕预期服务端效果判断，不是要求每次返回完全相同的 JSON。

## Idempotency keys and duplicate writes

An idempotency key must be bound to the caller scope and request fingerprint, with durable atomic deduplication. A client reuses one key for retries of the same logical operation. The server records the key, a canonical request fingerprint, and the outcome under a uniqueness constraint or equivalent atomic mechanism. Scope keys by tenant or principal as appropriate so one caller cannot replay another caller result. Reusing a key with a different payload must be rejected rather than returning an unrelated success. Concurrent duplicates need a defined in-progress behavior. If the business commit succeeds but the response is lost, a retry should retrieve the recorded outcome instead of repeating the side effect. Coordinate the business write and deduplication record in one transaction when possible; remote side effects require their own protocol. Expiry defines how long duplicate suppression lasts. A process-local map loses records on restart and is inconsistent across replicas, so it cannot alone promise durable cross-instance deduplication.

中文：同一次逻辑操作的重试应复用同一个幂等键。服务端要把键绑定到调用者范围和请求指纹，原子记录执行状态及结果；相同键配不同请求体应拒绝。业务提交成功但响应丢失时，重试应返回已有结果。业务写入与去重记录尽量在同一事务提交，远程副作用还需独立协调。进程内 Map 重启后丢失，多实例间也不一致，不能承诺持久去重。键的保留期限及并发执行中的响应规则都必须明确。

## HTTP status and client recovery

HTTP status codes should tell the client which class of recovery is meaningful. A 401 response signals a lack of valid authentication credentials and includes the applicable WWW-Authenticate challenge. A 403 response indicates that the server refuses the request, which may reflect insufficient permission even with valid credentials. Retrying the same credentials does not inherently fix forbidden access. A 400 response can describe malformed input; 422 can express well-formed content that fails semantic processing under the API contract. An unexpected server-side failure belongs in the 5xx family rather than being disguised as a successful 200 response with an error string. Preserve specific codes where clients need them, but do not expose internal exception messages, SQL, or tokens. Resource hiding may intentionally use 404 for inaccessible resources, so the security policy should be consistent. A status code is a protocol signal; a structured safe error body explains the particular problem.

中文：401 表示缺少有效认证凭据，并应携带适用的 WWW-Authenticate 挑战；403 表示服务器拒绝请求，常见原因是权限不足。400 可用于格式错误，422 可表达格式可解析但语义无法处理，具体应遵循接口约定。意外服务端故障应使用 5xx，不能包装成 200 后只在字符串里报错。错误体应提供安全、结构化的信息，避免泄露 SQL、令牌和异常栈。为隐藏资源是否存在，安全策略也可能一致地返回 404。

## Conditional updates and lost writes

Evaluate If-Match and commit the update atomically against the current resource version. The server supplies an entity tag for a representation. An editing client sends the tag it read in If-Match. If the current strong validator no longer matches, the server rejects the precondition, commonly with 412 Precondition Failed, and the client can fetch and reconcile the newer representation. The check cannot be a separate unprotected read followed by an unconditional write: another writer could slip between them. Implement the condition with a version-aware database update or equivalent concurrency control. A weak entity tag is not suitable for the strong comparison required by If-Match. If the API requires a condition to prevent lost updates, it can reject missing conditions with 428 under its documented contract. This protects one resource version, not arbitrary multi-resource business invariants. Do not confuse conditional editing with an idempotency key, which addresses duplicate requests rather than competing edits.

中文：服务端返回 ETag，客户端提交修改时用 If-Match 携带原版本。当前强校验器不匹配时应拒绝前置条件，常用 412，客户端再读取新版本并协调冲突。比较版本与提交写入必须原子完成，可用带版本条件的数据库更新；先查后无条件写仍有竞争窗口。If-Match 使用强比较，弱 ETag 不适合。若接口强制要求条件，可以按约定用 428 拒绝缺少条件的请求。此机制防止版本覆盖，幂等键则用于处理重复请求。

## Cursor pagination and stable ordering

Cursor pagination needs a deterministic ordering with a unique tie-breaker. With offset pagination, inserting or deleting rows before the next offset shifts positions, so the next page can repeat or skip items. A keyset cursor records the last observed ordering values, for example created_at plus id, and continues after that tuple. Sorting only by a non-unique timestamp is ambiguous. Treat a page token as opaque to callers, validate its structure, and bind it to the relevant filter, order, and tenant scope. A cursor is not authorization; every page request still needs resource checks. Keyset traversal reduces position-shift problems but does not automatically create a consistent snapshot when existing rows change ordering fields. If snapshot consistency is required, design explicit snapshot or watermark semantics. Limit page size, document expiration, and distinguish an empty page with a continuation token from the true end of a result set where the contract permits that distinction.

中文：offset 分页依赖行的位置，前面插入或删除数据会让后续页重复或遗漏。游标分页应按确定顺序继续，例如 created_at 加唯一 id，单用可能重复的时间戳不够。令牌对调用者应是不透明的，服务端要校验并绑定过滤条件、排序和租户范围。游标不能代替授权。已有记录的排序字段被修改时，keyset 也不自动提供一致快照；确需快照时应单独设计水位或快照语义。还要限制页大小并说明令牌失效和结束标记。

## Validation and resource ownership

Input validation does not establish permission to act on the referenced resource. Schema validation can reject malformed identifiers, excessive lengths, missing fields, and invalid enum values before expensive processing. It cannot prove that the authenticated user owns the order whose identifier appears in the request. Load or query the resource within the appropriate tenant scope and verify the requested operation is permitted. Business rules such as a nonnegative balance or allowed state transition also need enforcement near the write boundary, often with database constraints or transactional checks. Client-side validation improves feedback but is bypassable. Use an explicit allowlist of writable fields instead of binding arbitrary submitted properties onto persistent entities; otherwise callers may set owner, role, or internal status fields. Limit request body size and collection lengths as part of resource protection. Return field-level safe errors without reflecting credentials or unrestricted attacker input into logs and responses.

中文：字段类型和长度校验只能保证输入结构，不会证明调用者拥有请求中的订单。必须在正确租户范围内查询资源，并校验当前用户是否有权执行该操作。余额不能为负、状态转换是否合法等业务规则应靠近写入边界执行，必要时结合事务和数据库约束。前端校验可以绕过。服务端应明确允许写入的字段，避免直接把任意 JSON 绑定到实体，让调用者改写 owner、role 或内部状态。请求体大小和数组长度也应有限制。

## Structured problem responses

Use a stable problem type for programmatic handling and keep human-readable detail free to explain the occurrence. The application/problem+json representation can carry type, title, status, detail, and instance members. The type identifies the problem category; instance can identify a particular occurrence, and detail explains that occurrence safely. The HTTP response status remains authoritative for generic HTTP processing. An application may define documented extensions, such as validation field pointers or a correlation identifier, without exposing stack traces or secret configuration. Client behavior should depend on the documented type or stable extension code, not exact prose that may change or be translated. Keep retryability a separate explicit contract rather than assuming every 5xx result can be safely replayed. If streaming has already committed response headers, a later failure may need the stream protocol error event instead of a new HTTP status. A consistent error envelope improves handling, but it cannot by itself make failures safe or recoverable.

中文：Problem Details 可使用 application/problem+json，常见成员包括 type、title、status、detail 和 instance。type 表达稳定问题类别，detail 用安全文本说明本次问题，instance 可标识具体发生实例。客户端应依赖类型或约定扩展码，不应解析会翻译或变动的人类描述。HTTP 状态仍承担通用协议语义。可以扩展字段错误位置或关联编号，但不要泄露异常栈与密钥。流式响应头已提交后发生故障，通常需要使用流协议约定的错误事件。

## Retry budgets and jitter

Retry only eligible operations within a bounded attempt count and remaining deadline, with backoff and jitter. A transient failure may justify another attempt, but a timeout leaves uncertainty about whether the previous attempt took effect. Repeated writes therefore need safe semantics or a deduplication contract. Exponential backoff increases the delay between attempts; random jitter prevents a large client population from retrying in synchronized waves. Honor a usable Retry-After signal where applicable, while respecting the caller total deadline. Choose one responsible retry layer when possible: three attempts at each of several nested layers can multiply downstream calls. Do not retry deterministic validation errors or authorization failures without a meaningful state change. Record attempt count, delay, cause, and final outcome so hidden retries do not disguise latency. A circuit breaker or admission limit can reduce pressure during sustained failure, but it does not replace careful retry eligibility or correct duplicate handling.

中文：只有允许重试的操作才应在有界次数和剩余总预算内重试。超时不代表前次请求一定没执行，写操作还需要幂等或去重约定。指数退避拉长间隔，随机抖动避免大量客户端同步重试；适用时参考 Retry-After，同时不能越过总截止时间。多层各重试三次会相乘放大下游调用，应尽量明确负责重试的一层。校验或授权失败通常不应原样重试。应记录次数、原因、等待时间和最终结果，使延迟和失败可解释。

## End-to-end deadlines

An end-to-end deadline gives every stage only the remaining request budget. Separate five-second timeouts for three sequential calls can already consume roughly fifteen seconds, before queueing, retries, serialization, or cleanup. Establish a total request budget at entry and compute the remaining duration before each expensive stage. Include queue wait and retry delays, and refuse to start another attempt when no useful budget remains. Use a monotonic clock for elapsed-time calculations within a process; wall-clock corrections should not extend or shorten local time budgets unexpectedly. Across services, propagate a deadline or remaining duration using a documented contract and account for clock assumptions. A timeout response does not prove that the remote operation stopped, so cancellation and side-effect safety need separate handling. Reserve time to serialize the final response and release resources. Observability should separate queue delay, dependency time, and cleanup from the user-visible total.

中文：每一步单独允许五秒，三个串行调用就可能用掉十五秒，还没算排队和重试。应在入口建立总预算，各阶段只取得剩余时间，排队与退避等待也计入。预算不足时不要启动新尝试。进程内计算经过时间应使用单调时钟，避免系统时间调整影响。跨服务传播需要约定截止时间或剩余时长及其时钟假设。向客户端返回超时不证明远端任务已经停止，取消和副作用处理仍是独立问题。还应为响应序列化与资源清理留出时间。

## Rate limits and admission control

A rate limit bounds arrivals over time, while a concurrency limit bounds in-flight work. A token bucket accumulates tokens up to a capacity and spends a token per admitted request, allowing a controlled burst while limiting the longer-term rate. A semaphore-style concurrency cap instead prevents too many slow requests from consuming resources simultaneously. A service may need both because low request rate can still produce many in-flight calls when dependencies stall. Scope limits to the relevant caller or tenant, and define any global capacity protection separately. A 429 Too Many Requests response should explain the applicable limit safely and may provide Retry-After to guide the next attempt. Do not promise an exact retry instant when distributed counters are approximate. Cluster-wide enforcement requires coordinated state or an explicitly documented approximation; one independent per-process counter is not automatically a global quota. Rejections and queueing should be observable by limit reason.

中文：速率限制约束一段时间内进入多少请求，并发限制约束同时在途的工作量。令牌桶允许受控突发并限制长期速率；Semaphore 类限制保护同时占用的资源。下游变慢时，即使每秒速率不高，也可能积累很多在途请求，所以两者可能都需要。429 表示请求过多，可以提供 Retry-After 和安全的限制说明。分布式计数近似时不要承诺精确恢复瞬间。每实例各自计数并不自动成为整个集群的统一配额。

## SSE framing and reconnect semantics

An SSE event is a UTF-8 text block terminated by a blank line. The response uses text/event-stream. Fields such as event, data, id, and retry have defined meanings; consecutive data lines form one event payload, and comment lines can serve as heartbeats. The browser EventSource client can reconnect and send Last-Event-ID when it has a stored event ID, but replay requires the server to retain and order events and apply an explicit resume policy. An ID alone does not create a durable event log or prevent duplicated delivery. Consumers that apply non-idempotent changes must handle duplicates and gaps. Network disconnection, deliberate completion, and an application error event are distinct outcomes. Native EventSource is one-way server-to-client communication, and its constructor does not expose arbitrary request headers; authentication must fit the chosen client and transport contract. Avoid putting long-lived bearer secrets in URLs. Proxies and buffering can affect when users actually see events.

中文：SSE 使用 UTF-8 的 text/event-stream，空行结束一个事件，多条 data 行可组成同一事件内容。event、id、retry 等字段各有含义，注释行可作为心跳。浏览器重连可携带 Last-Event-ID，但可靠续传还需要服务端保存有序事件并定义恢复策略。事件编号本身不提供持久日志，也不能自动消除重复或缺口。主动结束、网络断开和应用错误应分别处理。原生 EventSource 构造器不能任意设置请求头，认证需要符合客户端能力；不要把长期令牌放进 URL。

## CORS is not authorization

CORS controls browser cross-origin response access; it does not authenticate the caller. A script running in a browser is constrained by the Fetch CORS protocol, but a direct HTTP client can send requests without obeying browser policy. The server must still authenticate the user and check resource permissions. Some cross-origin requests can be sent without a preflight, so hiding a response from a script does not necessarily prevent a state-changing request from reaching the server. Cookie-authenticated actions therefore need an appropriate CSRF design, using measures such as SameSite cookie policy, anti-CSRF tokens, and origin checks according to the application threat model. Credentialed CORS responses cannot simply combine wildcard Access-Control-Allow-Origin with credential access; return a validated specific origin where required. Never blindly reflect arbitrary Origin values. CORS mistakes can expose browser-readable data, while overly strict settings can break a legitimate frontend; test allowed and disallowed origins separately.

中文：CORS 约束浏览器脚本读取跨源响应，不会认证请求者；直接 HTTP 客户端无需遵守浏览器策略。部分跨源请求不预检也能发送，因此读不到响应并不代表请求没到服务端。Cookie 认证的修改操作仍需按威胁模型设计 CSRF 防护，如 SameSite、反 CSRF 令牌与来源检查。允许携带凭据时，不能简单使用通配 Access-Control-Allow-Origin。应验证具体 Origin，不能把任意来源直接反射回去，同时测试允许与拒绝的来源。

## Backward-compatible API evolution

Compatibility includes observable behavior and client assumptions, not only endpoint names. An old client may use an exhaustive switch for an enum and fail when the server returns an unfamiliar value. Requiring a new request field makes previously valid client requests invalid. Changing defaults, nullability, field meaning, ordering, pagination, or error behavior can also break consumers without changing a URL. Prefer additive optional request fields with preserved old behavior, and design response consumers to tolerate unknown fields where the contract allows. Unknown enum handling needs an explicit strategy such as an unknown branch; it should not silently map to a misleading known state. Publish a migration plan for intentional breaking changes, including coexistence and deprecation windows appropriate to actual clients. Schema snapshots and consumer contract tests help detect changes, but they cannot cover undocumented assumptions automatically. Version labels communicate a contract; they do not make a breaking implementation compatible.

中文：兼容性包括行为与客户端假设，不只是接口地址。旧客户端对枚举穷举分支时，新增枚举值可能让它崩溃；新增必填请求字段会让原本合法的调用失败。默认值、空值语义、排序、分页和错误行为变化同样可能破坏兼容。新增可选字段应保留旧行为，响应消费者在契约允许时容忍未知字段；未知枚举必须有明确处理分支，不能偷偷映射为错误的已知状态。破坏性变更需要迁移、共存和弃用方案，契约测试只能覆盖已表达的假设。

## Primary references

These are original explanations and design examples. RFC requirements and application policy are distinguished above; example recovery choices are not universal guarantees.

- [RFC 9110: HTTP semantics](https://www.rfc-editor.org/rfc/rfc9110.html)
- [RFC 6585: Additional HTTP status codes](https://www.rfc-editor.org/rfc/rfc6585.html)
- [RFC 9457: Problem details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)
- [WHATWG HTML: Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [WHATWG Fetch: CORS protocol](https://fetch.spec.whatwg.org/#http-cors-protocol)
- [Google AIP-158: Pagination](https://google.aip.dev/158)
- [Google AIP-180: Backwards compatibility](https://google.aip.dev/180)
- [AWS Builders Library: Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)
- [OWASP API Security: Broken object level authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)
- [OWASP: CSRF prevention cheat sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
