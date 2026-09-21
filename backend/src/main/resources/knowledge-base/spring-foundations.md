# Spring foundations / Spring 核心基础

Original bilingual notes for Spring Boot applications. Transaction and caching explanations assume the default proxy mode; exact defaults and available configuration options must be checked against the project's framework version. Teaching examples are not claims about implemented project features.

## Spring constructor injection and required dependencies

Constructor injection makes required dependencies part of an object's creation contract. A service can declare final repository and clock fields and receive those collaborators through its constructor, making missing dependencies visible immediately. Tests can construct the service with a small fake without starting a Spring application context. Field injection hides that contract and makes accidental partially initialized instances easier to create. A single constructor on a Spring component normally does not need an explicit Autowired annotation. If two constructors or several beans of the same type create ambiguity, resolve it deliberately with the appropriate constructor selection or qualifier. A long constructor parameter list can indicate too many responsibilities; replacing all dependencies with an untyped service locator only hides that design problem.

构造器注入把必需依赖变成对象创建契约。服务可用 final 字段保存仓储和 Clock，通过构造器一次性接收，测试时直接传入替身即可，不必启动整个 Spring。字段注入隐藏依赖，容易产生尚未正确初始化的对象。单构造器组件通常无需显式 @Autowired；多个候选 Bean 应通过明确的限定符解决。构造器参数过多可能说明职责过多，不能只改成运行时查找依赖来掩盖问题。

## Spring typed configuration and startup validation

Bind related settings to ConfigurationProperties and validate the resulting configuration before accepting traffic. A settings class or record can group a provider URL, request deadline, and retry limit, using types such as Duration instead of scattering string parsing through business code. Register the properties with configuration-properties scanning or explicit enablement, and use Validated with Bean Validation constraints when the validation dependency is available. Nested properties need cascading validation where appropriate. Invalid values should fail startup with a useful property name, while error output must avoid exposing secrets. Keep credentials in environment or secret-manager configuration rather than source control. Document effective precedence and test profile overrides so a local default does not silently replace the intended production setting.

相关配置应绑定到 @ConfigurationProperties，并在接收流量之前完成校验。用 Duration 等明确类型表达超时，用 @Validated 与 Bean Validation 约束检查必要值；嵌套对象需要适当级联校验，属性类也要通过扫描或显式启用注册。错误配置应使启动失败，并指出属性名称，但不能输出密钥。配置优先级和 profile 覆盖规则应有说明与测试，避免开发默认值悄悄覆盖正式环境参数。

## Spring transaction proxies and self invocation

In the default proxy mode, transactional advice runs when a call crosses the Spring proxy boundary. If one method on a service calls another method on the same instance through this, that internal call does not pass through the proxy and the callee's Transactional annotation does not create the intended boundary. This can make an integration test pass accidentally when its caller already starts a transaction. Move the transactional operation to a separate injected service or use TransactionTemplate when a programmatic boundary is clearer. Ensure the object is managed by Spring and the method is eligible for the chosen proxy mechanism. Do not assume private methods or manually constructed instances receive transactional interception. Verify transaction behavior at an external service entry point.

默认代理模式下，事务拦截只发生在调用经过 Spring 代理边界时。同一个实例内部通过 this 调用另一个标注 @Transactional 的方法，不会经过代理，因此不能按该注解建立新的事务边界。测试如果外层已经有事务，可能掩盖这个问题。可将操作拆到另一个注入的服务，或使用 TransactionTemplate 明确边界。手动 new 的对象、私有方法等不能被想当然地视为已获得事务拦截，应从外部入口验证。

## Spring rollback rules and swallowed failures

Without a custom rollback policy, declarative Spring transactions roll back for RuntimeException and Error, while checked exceptions do not trigger rollback by default. If a checked business failure must cancel writes, declare an appropriate rollbackFor rule or select a suitable global policy supported by the framework version. An exception caught and swallowed inside a transactional method usually cannot tell the interceptor to roll back; either propagate a meaningful failure or explicitly mark the transaction rollback-only when that is the intended contract. A rollback-only inner participant can cause the outer commit to fail with UnexpectedRollbackException. Test the resulting database state, not only the exception class, and avoid wrapping every failure in a success response that conceals whether the transaction committed.

没有自定义策略时，Spring 声明式事务默认对 RuntimeException 和 Error 回滚，受检异常默认不会触发回滚。受检业务异常需要取消写入时，可设置 rollbackFor 或框架版本支持的全局策略。如果在事务方法内部捕获后吞掉异常，代理通常无法据此回滚，应传播失败或明确设置 rollback-only。内部参与者标记仅回滚可能让外层提交抛出 UnexpectedRollbackException。测试必须验证数据库最终状态，不能只检查抛出了什么异常。

## Spring transaction propagation and connection demand

REQUIRED normally joins an existing transaction, while REQUIRES_NEW starts an independent physical transaction. The outer transaction is suspended for the independent inner operation, so inner commit or rollback is separate from the outer outcome. This is useful only when that independence matches the business rule, such as an audit record that must survive a failed primary operation. With common JDBC transaction managers, the outer connection may remain held while the inner transaction needs another connection. Concurrent requests can therefore exhaust a pool if it was sized as though each request needed only one connection. NESTED uses savepoints when supported and should not be equated with an independent physical transaction. Check manager support and verify both inner and outer outcomes with integration tests.

REQUIRED 通常加入已有事务，REQUIRES_NEW 则开启独立的物理事务，并在执行期间挂起外层事务。内层提交可以独立于外层结果，只有业务确实需要这种独立性时才应使用。常见 JDBC 事务管理器会让外层继续持有连接，而内层另取连接，并发量大时可能耗尽连接池。NESTED 在支持的管理器中使用保存点，不等于独立物理事务。测试应同时检查内外层结果与资源需求。

## Spring MVC request validation and nested objects

Validate request bodies at the HTTP boundary before business code uses them. In Spring MVC, a Valid or Validated annotation on a request-body argument can trigger Bean Validation on that object; constraints such as NotBlank and Size state what a field accepts. Nested objects require cascading validation, typically with Valid on the nested property, and NotNull is separate from constraints that allow null values. Deserialization errors and validation errors are different failure paths and should both map to a stable client error contract. Validation is not authorization: a syntactically valid record identifier still needs an ownership check. Keep database-dependent business rules in the service layer and avoid putting slow remote calls inside simple field validators.

在 HTTP 边界对请求体使用 @Valid 或 @Validated，可以触发 Bean Validation；@NotBlank、@Size 等约束描述字段要求。嵌套对象通常还需在属性上添加 @Valid 才会级联检查，不能为空则应另加 @NotNull。JSON 反序列化失败与约束校验失败是不同路径，都需要稳定的客户端错误格式。参数合法不等于有权访问该记录，仍需所有权校验；依赖数据库的业务规则应保留在服务层。

## Spring API errors and ProblemDetail

A consistent HTTP error response separates machine-readable identity from human-readable explanation. Spring supports ProblemDetail for standardized problem fields, and ControllerAdvice can map known exceptions into a shared response contract. Choose status codes from the actual failure: malformed input, missing resource, forbidden action, and unavailable dependency have different meanings. Include a stable application error code or problem type so clients do not need to parse the detail text. A request or trace identifier can connect a public failure to server diagnostics without exposing stack traces, SQL, tokens, or prompts. An HTTP 200 response carrying an error-shaped JSON body confuses intermediaries and client retry logic. Test status, content type, and body together at the controller boundary.

统一错误响应应把机器可识别的错误标识与给人阅读的说明分开。Spring 的 ProblemDetail 可承载标准问题字段，@ControllerAdvice 可集中转换已知异常。状态码应反映输入错误、资源不存在、权限拒绝或依赖不可用等不同情况，客户端不应解析自然语言 detail 来判断错误。可以公开请求标识关联日志，但不能泄露堆栈、SQL、令牌或提示词。错误包在 HTTP 200 里会误导客户端与中间层，应一起测试状态码、类型和正文。

## Spring singleton services and request state

A singleton bean is shared within its container, so mutable per-request fields can leak across concurrent requests. A controller field named currentUser or conversationBuffer is therefore unsuitable for storing the active request's identity or output, even when each handler appears straightforward in isolation. Keep request data in method-local variables and pass it explicitly to collaborators. Use a request-scoped component when the lifetime genuinely belongs to one HTTP request, with the appropriate scoped injection mechanism when a longer-lived bean uses it. Shared caches or counters require their own concurrency policy. Injecting a prototype bean once into a singleton does not create a new prototype instance for every later method call. Scope describes lifecycle; it is not a general thread-safety guarantee.

singleton Bean 在容器内共享，把 currentUser 或 conversationBuffer 保存为可变实例字段会造成请求之间的数据串扰。请求身份与输出应放在方法局部变量中并显式传递，确需请求生命周期时再使用 request scope，并处理跨作用域注入。共享缓存或计数器还要有独立并发策略。向 singleton 注入一次 prototype，并不会让每次方法调用都自动得到新对象。作用域只描述生命周期，不自动保证线程安全。

## Spring Data JPA fetch plans and N plus one

Loading a page of entities and then reading a lazy association for every row can produce an N plus one query pattern. The initial query loads the page, and subsequent access may issue one additional query per associated object or collection. Define a fetch plan for the use case: a DTO projection, an entity graph, a suitable fetch join, or batched fetching can reduce unnecessary round trips. Fetching everything eagerly is not a universal fix and may load much more data than the response needs. Collection fetch joins require particular care with pagination because joins multiply rows; verify generated SQL and provider behavior. Map the required data inside a deliberate persistence boundary instead of relying on serialization to initialize arbitrary lazy relations.

先分页加载实体，再逐行访问懒加载关联，可能产生 N+1 查询。可根据用例选择 DTO 投影、实体图、合适的 fetch join 或批量抓取，而不是把所有关联一律改成 eager。集合关联的 fetch join 会扩展连接结果行，和分页一起使用时需要检查 SQL 与持久化实现行为。应在明确的持久化边界内提取响应所需数据，不能依赖 JSON 序列化意外触发懒加载；测试还要观察查询数量。

## Spring testing slices and server boundaries

A test slice loads a selected application layer, while a full-context test checks broader wiring. WebMvcTest is useful for controller routing, validation, serialization, and error contracts with service collaborators replaced as needed. DataJpaTest focuses on persistence configuration and repository behavior, but the selected database must match the behavior under examination. A SpringBootTest with a mock web environment does not prove a real socket was opened; a random-port test exercises a running HTTP server. Even that does not automatically verify browser rendering, proxy buffering, production authorization, or an external model provider. Choose the smallest boundary that can expose the failure and describe what the test actually exercised. Keep deterministic local service doubles for offline CI and report live integration checks separately.

测试切片只加载选定层；@WebMvcTest 适合路由、校验、序列化和错误契约，@DataJpaTest 适合仓储行为，但数据库类型要符合要验证的语义。@SpringBootTest 的 mock Web 环境不代表真正打开网络端口，随机端口模式才会启动 HTTP 服务。即使 HTTP 集成通过，也不自动证明浏览器渲染、代理缓冲或真实模型服务正常。测试应选择足以发现问题的最小边界，并明确记录覆盖范围；离线替身结果与真实集成结果要分开。

## Spring Actuator exposure and authorization

Enabling a management endpoint and exposing it over HTTP are separate configuration decisions. Actuator can provide operational signals, but a useful health or metrics endpoint should not imply that environment, configuration, loggers, or administrative operations are public. Expose only the endpoints required for the operating model, control network reachability, and enforce authorization with the actual security configuration. A custom SecurityFilterChain changes which defaults remain applicable, so verify the resulting access rules rather than assuming the starter protects every path. Public liveness can be minimal while detailed diagnostics require trusted access. Avoid putting credentials or user prompts in metric labels or health details, and test both an authorized operator and an unauthenticated caller against management routes.

启用管理端点与通过 HTTP 暴露端点是两项不同配置。需要健康与指标信息，不表示应公开环境变量、配置、日志级别或管理操作。只暴露运维必需端点，限制网络可达范围，并在实际安全配置中校验权限。定义自有 SecurityFilterChain 后，应验证默认行为是否仍适用，不能想当然地认为所有路径都受到保护。公开存活检查可保持简洁，详细诊断需可信访问；指标标签和健康详情不能包含凭据或用户提示词。

## Spring caching keys invalidation and proxy calls

A cache key must include every input that can change the result, including tenant or permission scope when applicable. Cacheable may skip method execution on a hit, so omitted identity inputs can return another user's result. Define an invalidation or expiration policy for changing data, and verify whether eviction occurs before or after the associated operation. The cache abstraction does not itself choose a universal time-to-live; that depends on the provider and configuration. With default proxy-based caching, self-invocation does not pass through the cache interceptor, just as other proxy advice can be bypassed. Cache successful reusable values deliberately; caching a temporary dependency failure as an empty list can hide recovery. Measure hit rate and correctness separately.

缓存键必须覆盖所有影响结果的输入，必要时包括租户和权限范围，否则命中后可能把别人的结果返回给当前用户。数据变化需要明确失效或过期策略，并验证驱逐与业务操作的先后关系。Spring 缓存抽象不统一决定 TTL，具体由提供者和配置控制。默认代理模式下，同实例内部调用不会经过缓存拦截器。不要把临时依赖故障随意缓存成空列表；命中率高也不代表内容正确。

## Spring after commit events and durable delivery

An AFTER_COMMIT transactional event listener runs after a successful transaction outcome, but an in-process event is not a durable message queue. It can prevent sending a notification for a transaction that rolls back, yet the process may still crash after database commit and before the notification reaches its destination. When delivery must survive restarts, write an outbox record in the same database transaction as the business change, then let a separate dispatcher send it with retries and an idempotency key. A listener triggered after commit should not assume new writes will be committed in the completed transaction; a separate transaction may be needed. Treat this as a consistency and delivery design choice, and test failures at the commit-to-dispatch boundary.

AFTER_COMMIT 事务事件监听器在成功提交后运行，但进程内事件不是持久消息队列。它可避免回滚事务发送通知，却无法防止提交后、通知送达前进程崩溃而丢失消息。需要重启后仍能投递时，应把 outbox 记录与业务修改写入同一事务，再由独立分发器重试发送，并使用幂等键。提交后监听器中的新写入也不能假设会被已完成的事务再次提交，必要时需独立事务。应专门测试提交与分发之间的故障。

---

Primary references:

- [Spring dependency injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)
- [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Transactional annotation and proxy boundaries](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Declarative rollback rules](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)
- [Transaction propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [Spring MVC validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)
- [Spring MVC error responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Bean scopes](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html)
- [Spring Data JPA query methods and entity graphs](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [Testing Spring Boot applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Actuator management endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
- [Cache annotations and proxy behavior](https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html)
- [Transaction-bound events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
