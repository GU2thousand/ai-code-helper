# System design foundations / 系统设计基础

Original bilingual study notes. Examples describe design tradeoffs, not a claim that this project operates the named infrastructure. / 原创双语学习笔记；示例解释设计权衡，不表示本项目已运行文中基础设施。

## User journeys and service objectives / 用户路径与服务目标

Begin system design with the user journeys that must succeed, the traffic shape, and the consequences of failure. A service-level indicator is a measured property such as the fraction of valid requests completed successfully or the fraction below a latency threshold. A service-level objective sets a target for that indicator over a defined window. State the denominator, eligible requests, measurement location, and exclusions so the target is reproducible. For a request-based availability objective, the error budget is the allowed fraction of eligible failed requests; a time-based indicator has a different denominator. A service-level agreement is a commitment that may have consequences for missing it, not a synonym for an internal objective. Select meaningful targets rather than assuming every function needs 100% availability. Averages can hide a slow tail, so latency objectives should make the measured population and threshold explicit.

系统设计先明确关键用户路径、流量形态与失败后果。SLI 是成功率或延迟达标比例等测量指标，SLO 是指标在指定窗口内的目标；分母、有效请求、测量位置与排除规则必须清楚。请求成功率的错误预算按请求计算，不能随意换算成停机分钟。SLA 可能带违约后果，与内部目标不同。延迟平均值可能掩盖尾部慢请求，目标应说明统计人群与阈值。

Primary references / 一手参考：[Google SRE: Implementing SLOs](https://sre.google/workbook/implementing-slos/)

## Capacity planning and bottlenecks / 容量规划与瓶颈

Translate assumptions into resource demand before selecting a machine count. Estimate peak and sustained request rates, read/write ratio, payload sizes, retention, replication copies, and growth. Storage includes indexes, metadata, replicas, and headroom rather than just raw business payloads. Network capacity depends on fan-out and replication as well as client traffic. A throughput estimate needs the measured cost per operation and the limiting resource: CPU, memory, disk, network, connections, or an external service. If one of three replicas fails, the two survivors must carry the admitted workload within the objective. Evaluate that degraded condition instead of sizing only for the average day. Back-of-envelope arithmetic is an explicit model, not a measured benchmark. Calibrate it with representative load tests and revisit the assumptions when request mix changes. Adding application replicas will not remove a saturated shared database bottleneck.

容量规划需把峰值与持续请求率、读写比例、数据大小、保留期和副本数转成资源需求。存储还含索引、元数据和余量；网络还受扇出与复制影响。瓶颈可能在 CPU、磁盘、连接或外部服务。三副本失去一个时，剩余两个仍应承受准入负载。估算是模型，不是压测结果，应以代表性负载校准；共享数据库已饱和时，多加应用副本不能消除该瓶颈。

Primary references / 一手参考：[Google SRE: Non-Abstract Large System Design](https://sre.google/workbook/non-abstract-design/)

## Stateless replicas and external state / 无状态副本与外部状态

Horizontal scaling adds service instances and distributes work among them. Interchangeable request handlers are easier to replace when durable application state is stored in an appropriate shared service rather than only in process memory. A user session kept solely in one process can disappear on restart or become unavailable when the next request reaches another replica. Sticky routing can preserve affinity temporarily, but it does not make that state durable. Shared state introduces its own consistency, capacity, and availability requirements. Stateful workloads may require stable network identities, persistent volumes, and controlled replacement; Kubernetes StatefulSet manages those identities and associations, not the application's database correctness. Multiple replicas do not automatically coordinate writes or prevent conflicting updates. Plan graceful termination so admitted requests can finish or fail clearly, and distinguish a process being alive from its ability to serve useful traffic.

横向扩展通过增加实例分担工作；处理器可互换时更易替换，持久业务状态应放在合适的共享存储。仅在进程内保存的会话会受重启和请求换副本影响，粘性路由不等于持久化。共享状态又带来一致性、容量和可用性要求。StatefulSet 管理稳定身份与卷关联，不自动证明数据库写入正确。多副本本身无法协调冲突写入，存活也不等于能提供有效服务。

Primary references / 一手参考：[Kubernetes: StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)

## Cache-aside and stale reads / 旁路缓存与陈旧读取

In cache-aside, the application checks the cache first, reads the source of truth on a miss, and then fills the cache. A common write path updates the authoritative store and invalidates the cached entry. This reduces repeated reads but does not by itself guarantee strong consistency. A reader can fetch an older database value before a write and refill it after invalidation, leaving a stale cache entry. Time-to-live bounds residence time only under the actual expiration and refresh behavior; it is not a proof that every read is current. Independent local caches and writes that bypass the invalidation path need special attention. Choose expiration and invalidation according to acceptable staleness and data sensitivity. Cache failure should have a planned effect on the backing store, because an abrupt wave of misses can overload it. Measure hit rate together with stale-read behavior and origin load.

旁路缓存先查缓存，未命中再查权威存储并回填；常见写路径先更新数据库，再失效缓存。该模式本身不保证强一致：读请求可能先拿到旧值，随后在写入失效之后回填旧值。TTL 需结合实际过期和刷新行为理解，不能证明每次读都最新。多实例本地缓存与绕过失效路径的写入都需处理；缓存失效造成集中回源时，还可能压垮数据库。

Primary references / 一手参考：[Microsoft Azure: Cache-Aside pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/cache-aside)

## Replication lag and read visibility / 复制延迟与读取可见性

Replication copies changes to other nodes for availability or read scaling, but the acknowledgment point determines what a successful write means. PostgreSQL streaming replication is asynchronous by default: a primary can acknowledge a commit before a standby has received or replayed it. A read sent immediately to that standby can therefore return older data, and failover can lose recent commits that were not replicated. Synchronous replication waits for configured standby acknowledgments and adds latency and dependency on those standbys. Receipt, durable flush, and application of a change are different stages. PostgreSQL remote_apply waits for replay on the selected synchronous standby, making the committed change visible to queries there; it does not promise visibility on every possible read replica. For read-your-writes behavior, choose a compatible read route or wait condition. Measure lag and test the actual failover and routing policy.

复制用于可用性与读扩展，但写成功的含义取决于确认阶段。PostgreSQL 流复制默认异步，主库确认时备库可能尚未收到或回放，因此立即读备库可能旧读，故障切换也可能丢失未复制提交。同步复制增加等待与依赖；接收、落盘和回放不同。remote_apply 等待选定同步备库回放并可见，不代表任意读副本都最新。读己之写需要匹配的路由或等待条件。

Primary references / 一手参考：[PostgreSQL: Standby Servers](https://www.postgresql.org/docs/current/warm-standby.html)

## Shard keys and hot partitions / 分片键与热点分区

Sharding partitions a dataset across independently managed storage units. Select a shard key using the main query patterns, key cardinality, distribution, and future growth, not only a desire for more machines. Range partitioning can keep nearby values together for efficient range queries, but monotonically increasing keys can concentrate new writes in one partition. Hash partitioning usually spreads different keys more evenly while making range queries less local. Hashing does not split the traffic for one extremely popular key: that key can remain hot. Queries without a useful shard key may require scatter-gather across many shards. Cross-shard transactions, joins, uniqueness, and rebalancing add coordination and operational cost. Record how data moves when capacity changes and how clients find the correct shard during migration. Sharding solves a particular scale limit; it does not automatically improve correctness or eliminate shared bottlenecks.

分片按键把数据分到不同存储单元，选键应结合查询模式、基数、分布和增长。范围分片有利范围查询，但递增键可能让新写集中一个分区；哈希通常均衡不同键，却不能拆散同一个热门键的流量。缺少分片键的查询可能需跨片聚合，跨片事务、连接、唯一性和重平衡均增加协调成本。扩容时的数据迁移与路由也应提前设计。

Primary references / 一手参考：[Microsoft Azure: Sharding pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/sharding)

## Message delivery and business effects / 消息投递与业务副作用

A broker's delivery guarantee describes message transport, while a business effect may occur in a separate database or external service. In at-least-once delivery, a message can be delivered again after processing if the acknowledgment was lost or the consumer failed before acknowledging. Amazon SQS Standard queues explicitly permit duplicates and only offer best-effort ordering. A consumer must therefore tolerate redelivery and avoid assuming arrival order equals business order. Use an event identity, enforce deduplication together with the protected local state change where possible, and define how long deduplication records must remain. Acknowledge only after the required durable processing succeeds, according to the broker's protocol. Repeated delivery and repeated business effects are different outcomes. Claims of end-to-end exactly-once behavior require a precisely stated boundary and mechanism; a broker feature alone cannot prove that an unrelated external side effect occurs once.

Broker 的投递保证描述传输，业务副作用可能发生在另一数据库或外部服务。至少一次投递允许处理后因确认丢失而再次送达；SQS Standard 还只提供尽力排序。消费者应能处理重复与乱序，用事件标识去重，并尽可能把去重记录和本地业务修改放在同一原子边界。持久处理成功后才按协议确认。消息只投递一次与业务副作用只发生一次不是同一个保证。

Primary references / 一手参考：[Amazon SQS: Standard queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues.html)

## Transactional outbox and publication gaps / 事务发件箱与双写间隙

Writing a database record and publishing an event are two actions that can fail independently. If the process crashes between them, the system may have business state without its event or an event describing a state that never committed. The transactional outbox stores the business change and an outbox event in the same local database transaction. A separate relay reads committed outbox entries and publishes them, then records publication progress. The relay can crash after publishing but before recording progress, so duplicate publication remains possible. Consumers still need duplicate-tolerant processing. Preserve event ordering where the business requires it, usually by an aggregate key and sequence rather than assuming one universal order. Monitor relay backlog, age, and failures and define retention or cleanup. The pattern closes a local database-to-broker publication gap; it does not make every downstream service part of one atomic transaction.

数据库更新与消息发布各自可能失败，进程在两步之间崩溃会形成双写间隙。事务发件箱把业务修改和待发事件放进同一个本地数据库事务，提交后由独立 relay 发布并记进度。relay 若发布成功后、记进度前崩溃，仍会重复发布，所以消费者仍需容忍重复。还应监控积压年龄、处理业务需要的顺序与清理。此模式不等于所有下游服务共享一个原子事务。

Primary references / 一手参考：[AWS: Transactional outbox pattern](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)

## Consensus majorities and partitions / 共识多数派与网络分区

A consensus group maintains agreement by requiring a quorum for committing updates. For an etcd voting cluster, a majority is floor(N / 2) + 1 members. Three voting members require two votes and can continue after one member fails; five require three and can continue after two fail. Losing a majority prevents normal progress on new updates, even if some machines remain reachable. A network partition does not create two independent majorities of the same fixed membership, which is central to avoiding conflicting commits. Odd membership is economical for a desired failure tolerance; it does not ensure every possible partition can make progress. Additional members also add replication and coordination work. Placing members across distant regions increases communication latency, so select placement from the failure model and latency objectives. Distinguish the guarantee of committed writes from the chosen read consistency mode.

共识提交更新需要法定人数。etcd 投票集群多数为 floor(N / 2) + 1：三节点需两票可容忍一个故障，五节点需三票可容忍两个。失去多数后，新更新不能正常推进，即使仍有机器可达。固定成员集合的网络分区不能产生两个独立多数。奇数成员是故障容忍成本上的选择，不保证任意分区都可用；跨地域通信还会增加提交延迟。写提交保证与读取一致性模式也需分开。

Primary references / 一手参考：[etcd: FAQ](https://etcd.io/docs/v3.6/faq/)

## Queue backpressure and bounded work / 队列背压与有界工作量

A queue can absorb a temporary mismatch between arrival and processing rates, but sustained arrivals above service capacity create growing backlog and latency. Track the age of the oldest waiting message as well as queue length, because a short queue can still contain stalled work. Backpressure slows the producer or limits admitted work to protect the consumer and downstream systems. RabbitMQ consumer prefetch limits unacknowledged messages in flight to a consumer; it is not the maximum number of messages stored in a queue. Broker flow control can separately slow publishers when internal resources are under pressure. Bound concurrency, retries, and storage, and choose a clear overflow policy such as rejection, deferral, or an explicitly accepted drop. A higher prefetch may improve throughput but also increases in-flight memory and can worsen fairness. Validate settings with the real work duration and failure behavior.

队列能吸收短期生产消费速率差，持续输入大于处理能力仍会积压并增加延迟；应同时监控长度和最老消息年龄。背压通过减慢生产或限制准入保护下游。RabbitMQ prefetch 限制消费者未确认的在途消息，不等于队列最大长度；broker flow control 是另一种发布限速机制。并发、重试与存储应有边界，溢出行为需明确。提高预取可能提升吞吐，也会增加内存和影响公平性。

Primary references / 一手参考：[RabbitMQ: Consumer Prefetch](https://www.rabbitmq.com/docs/consumer-prefetch) · [RabbitMQ: Flow Control](https://www.rabbitmq.com/docs/flow-control)

## Observability signals and storage / 可观测信号与存储

Metrics summarize measured behavior over time, logs record individual events, and traces relate work across a request's path. These signals complement one another: a latency histogram can reveal a slow tail, while a trace can show which dependency consumed time. Propagate trace context across supported process boundaries and connect relevant logs to the same trace identity. OpenTelemetry provides instrumentation, collection, processing, and export; it is not itself the telemetry storage or visualization backend. Exporting spans therefore does not prove that a complete trace can be searched later. Configure a receiving backend, retention, access controls, and sampling, and verify a representative request end to end. Avoid secrets and unnecessary personal data in attributes and messages. High-cardinality metric labels can make aggregation expensive; request or user identifiers generally belong in controlled event or trace context rather than unbounded metric dimensions.

指标汇总随时间变化的测量，日志记录事件，trace 关联一次请求经过的工作。延迟直方图能暴露慢尾，trace 帮助定位耗时依赖。OpenTelemetry 提供埋点、收集、处理和导出，本身不是存储或可视化后端；仅导出 span 不代表之后能检索完整链路。需配置接收后端、保留期、权限和采样并做端到端验证。属性不应泄露密钥，多变的用户或请求标识也不宜成为无界指标标签。

Primary references / 一手参考：[OpenTelemetry: Signals](https://opentelemetry.io/docs/concepts/signals/) · [OpenTelemetry: What is OpenTelemetry](https://opentelemetry.io/docs/what-is-opentelemetry/)

## Disaster recovery objectives / 灾难恢复目标

Recovery point objective, RPO, expresses the maximum tolerable data loss in time relative to an incident. Recovery time objective, RTO, expresses the tolerated time to restore the required service after disruption. These are business requirements, not numbers automatically guaranteed by a backup schedule. A nightly backup alone can leave a large recovery gap, and a backup that cannot be restored does not meet an operational recovery goal. Choose backup, replication, geographic separation, and recovery procedures from the agreed targets and failure scenarios. Replicas can copy accidental deletion or corrupted data, so replication is not a substitute for recoverable historical backups. Test restoration, credentials, dependencies, routing, and data consistency in exercises, and measure the achieved recovery time and data point. Routine high availability within one environment does not establish readiness for the loss of that entire environment.

RPO 表示事故时最多容忍多长时间的数据丢失，RTO 表示恢复所需服务可容忍多长时间。它们是业务要求，不会由备份频率自动保证。只有备份文件却无法恢复，不能满足恢复目标；副本还可能同步误删或损坏，因此复制不能替代可恢复历史备份。演练需验证恢复、凭据、依赖、路由和数据一致性，并记录实际时间与恢复点。单环境高可用不等于整个环境丢失后的灾备能力。

Primary references / 一手参考：[AWS Well-Architected: Plan for Disaster Recovery](https://docs.aws.amazon.com/wellarchitected/latest/reliability-pillar/plan-for-disaster-recovery-dr.html)

## Service boundaries and asynchronous workflows / 服务边界与异步流程

Choose a service boundary around cohesive business ownership and a clear data contract. A synchronous request-response interaction makes the caller wait for a result and couples its completion to downstream availability and latency. Asynchronous messaging lets a workflow continue after accepting durable work and can smooth short bursts, but the result may only become visible later. The contract must explain acceptance versus completion, result tracking, failures, duplicates, ordering, and cancellation. Nonblocking client code does not by itself make the business protocol asynchronous: a future that must finish before returning a response still leaves the request dependent on that result. A chain of synchronous services accumulates latency and failure dependencies. Messaging moves some of that complexity into coordination and eventual state transitions rather than removing it. Split services when independent ownership or scale warrants the operational cost, and specify which component owns each authoritative record.

服务边界应围绕内聚业务责任和清晰数据契约。同步请求响应让调用方等结果，完成受下游延迟与可用性影响；异步消息可先持久接收工作再逐步执行，但结果稍后可见。契约应区分已接收与已完成，并处理状态追踪、失败、重复、顺序和取消。用 future 或非阻塞客户端不代表业务协议已异步，若返回前仍必须等待结果，依赖仍存在。拆服务需明确权威数据所有者并权衡运维成本。

Primary references / 一手参考：[Microsoft Azure: Interservice communication](https://learn.microsoft.com/en-us/azure/architecture/microservices/design/interservice-communication)
