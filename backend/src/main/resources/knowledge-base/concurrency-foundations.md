# Concurrency foundations / 并发基础

Scope: shared-memory Java concurrency, cancellation, and resource limits. These examples explain contracts; they are not workload-specific performance measurements.

## Atomicity and compound updates

A volatile counter increment is a read-modify-write operation, not an atomic increment. Two workers can both read 10, calculate 11, and each store 11. Visibility of each read and write does not make the whole sequence indivisible. AtomicInteger.incrementAndGet provides one atomic numeric update; a synchronized block can protect a larger invariant involving several fields. Every participant must use the same coordination rule. Locking writers while leaving readers unsynchronized is insufficient when readers need a consistent multi-field snapshot. LongAdder is useful for high-contention statistics, but sum is not an atomic snapshot across concurrent updates, so it is inappropriate for allocating unique sequence numbers. Tests that pass once do not establish correctness: identify the interleaving and the invariant first, then choose a primitive that protects the entire invariant. Avoid replacing one race with several individually atomic variables whose combined state can still be inconsistent.

中文：volatile 计数器的自增仍然包含读取、计算和写入三个步骤；两个线程可能读取相同旧值，导致一次增量丢失。AtomicInteger 可完成单个原子自增，synchronized 可保护多个字段组成的不变量。所有访问者必须遵守相同同步约定。LongAdder 适合统计吞吐量，但并发执行时 sum 不是整体原子快照，不应拿来生成唯一序号。测试偶尔通过不能证明没有竞争，应先画出线程交错过程并明确需要保护的状态。

## Visibility and happens-before

A happens-before relationship provides a visibility guarantee; elapsed time does not. If a producer stores a result and then writes true to a volatile done flag, a consumer that reads that volatile write sees the preceding result writes. The order matters: publishing the flag before completing the result breaks the intended protocol. Releasing a monitor and later acquiring the same monitor also establishes visibility, as do the applicable start and join rules. Thread.sleep merely suspends execution and does not publish ordinary fields. A successful experiment with a long delay cannot substitute for synchronization. Volatile is a useful publication signal for a one-way state transition, but it does not make arbitrary compound mutations safe. If the result remains mutable after publication, protect subsequent writes separately or publish an immutable snapshot. Prefer an established queue or Future where it already models the handoff.

中文：happens-before 提供跨线程可见性保证，等待时间本身没有这个作用。生产者应先写入结果，再写 volatile 完成标志；消费者观察到该次标志写入后才能依赖之前的结果写入。释放并重新获取同一把锁也能建立可见性。sleep 不会把普通字段自动变成线程安全字段。发布后继续修改对象时，还需要额外同步或不可变快照。队列和 Future 往往比手写轮询协议更合适。

## Deadlock and lock ordering

A global lock order prevents the circular wait in two-account transfers. Suppose one transfer holds account A while requesting B, and another holds B while requesting A. Neither can proceed because each waits for a resource retained by the other. Assign each account a stable unique ordering key and always acquire the lower key first, regardless of transfer direction; release locks in finally blocks. When ordering keys can collide, define a deterministic tie-breaking mechanism rather than silently assuming uniqueness. Avoid calling unknown callbacks or remote services while holding these locks, since their hidden dependencies enlarge the lock graph. Timed tryLock can help abandon an attempt, but it needs cleanup and a bounded retry strategy; retries alone can create livelock. Capture a thread dump to confirm a wait cycle instead of labeling every slow request a deadlock. A single lock is simpler but may reduce concurrency.

中文：转账线程分别持有 A 和 B，再互相等待对方的锁，会产生循环等待。按照稳定且唯一的账户排序键获取锁，可以消除这类环路；转账方向不能决定加锁顺序。排序键可能冲突时必须定义额外规则。用 finally 释放已经取得的锁，持锁期间尽量避免远程请求和未知回调。超时加锁必须配套清理与有界重试，否则可能变成活锁。线程转储中的等待环路比单纯观察接口变慢更可靠。

## Condition predicates and wakeups

A wakeup is a reason to recheck the predicate, not proof that the predicate is true. A consumer of a bounded queue should hold the associated lock, check whether the queue is empty in a while loop, and await while it is empty. Await releases that lock while waiting and reacquires it before returning. Spurious wakeups are possible; another awakened consumer may also consume the item before this consumer resumes. Both cases require rechecking the predicate. Producers must change the shared state while following the same lock protocol and then signal an appropriate condition. Waiting outside the lock can lose the relationship between the check and the state change. Multiple predicates may justify separate conditions such as notEmpty and notFull. Interruption and timeout are alternate outcomes, so code must not report successful consumption simply because the wait ended. Prefer BlockingQueue when it already supplies the required behavior.

中文：唤醒只意味着应重新检查条件，并不保证条件已经满足。消费者应持有对应锁，在 while 循环中检查队列是否为空；await 等待时释放该锁，返回前重新获取。虚假唤醒可能发生，其他消费者也可能抢先取走元素，所以 if 不够。生产者必须遵循相同锁协议更新状态并发出信号。中断和超时属于其他退出原因，不能当作成功消费。已有 BlockingQueue 满足需求时，应优先复用其成熟实现。

## Bounded executors and saturation

Bounding worker threads does not bound the executor queue. When incoming work stays above completion capacity, queued tasks retain request objects, buffers, and deadlines even if only a few threads execute. Choose a finite queue capacity together with a finite worker limit, then define what happens when both are full. A rejection should become an explicit overload result instead of disappearing silently. CallerRunsPolicy can slow a producer, but it may block an HTTP event-loop or latency-sensitive submission thread, so it is not a universal default. Queue age matters as much as queue length: reject work whose deadline is already spent. Increasing maximumPoolSize does not rescue an executor using an unbounded queue, because it normally keeps queuing after the core workers are busy. Measure arrival rate, service time, saturation, and tail latency before changing capacities; a larger queue can hide overload while making responses slower.

中文：固定线程数不等于资源有界，无界任务队列仍会保留请求对象和缓冲区并耗尽内存。应同时限定工作线程数量与队列容量，饱和时返回明确的过载结果。CallerRunsPolicy 会占用提交者线程，不适合随意用在事件循环上。使用无界队列时，仅提高 maximumPoolSize 通常不会增加实际线程。还应检查队列等待时间与请求剩余预算，已经超时的任务不值得继续排队。调参应依据饱和度、完成速率和尾延迟。

## Cooperative interruption and cancellation

Interruption requests cooperative cancellation; it does not forcibly terminate arbitrary Java code. A blocking method may throw InterruptedException and clear the interrupt flag. If the current layer cannot propagate that exception, usually restore the flag with Thread.currentThread().interrupt(), release owned resources, and return through the cancellation path. Continuing the loop after swallowing the exception can make shutdown ineffective. CPU-bound loops should periodically check interruption or another cancellation token. Future.cancel(true) attempts to interrupt a running task when supported; code that ignores interruption can keep running and retain locks, connections, or remote work. Closing a resource may be necessary to unblock an operation whose API does not respond to interruption. Separate the caller-visible cancelled state from evidence that cleanup completed. Do not use interruption as permission to commit partial business state. Cancellation contracts should specify what remains durable and whether a later retry is safe.

中文：中断是协作式取消请求，不是强行终止任何 Java 代码。阻塞调用抛出 InterruptedException 后可能清除标志；当前层无法继续抛出时，通常应恢复中断标志、清理资源并退出取消路径。吞掉异常后继续循环会妨碍停机。Future.cancel(true) 只能尝试中断，忽略中断的任务仍可能运行。调用者看到取消不代表连接或远程工作已经释放。应明确取消后的持久化状态和重试安全性，不能把部分结果冒充正常提交。

## CompletableFuture composition and blocking

thenCompose flattens an asynchronous stage returned by a callback. Use thenApply when the callback computes an ordinary value; if it returns another CompletableFuture, thenApply produces a nested future. thenCompose models the dependency directly without forcing the caller to block and unwrap it. A worker that calls join on another task queued to the same fully occupied executor can prevent that dependency from ever starting. This is starvation caused by scheduling dependencies, even without a monitor-lock cycle. Non-async continuations can run on a completing thread, while async variants use the supplied executor or their documented default. Choose an explicit executor for blocking work and keep its limits visible. CompletionStage composition does not automatically impose timeouts, undo side effects, or cancel every downstream operation. Handle exceptional completion deliberately and retain the original cause for diagnostics while keeping sensitive details out of client errors.

中文：thenApply 用于把结果转换成普通值；回调返回新的 Future 时，应使用 thenCompose 展平异步阶段。在线程已占满的小线程池里 join 等待同一线程池中尚未运行的任务，会导致依赖无法得到执行机会。非 async 回调可能由完成前一阶段的线程执行，阻塞工作应选择明确的执行器。组合 Future 不会自动完成超时控制、副作用回滚或整条调用链的取消。异常完成也需要清晰的处理路径。

## Concurrent maps and atomic entry updates

Thread-safe map methods do not make a multi-call check-then-act sequence atomic. Between containsKey returning false and put executing, another thread can install a value. Use putIfAbsent for installing an already-created value, or computeIfAbsent for an appropriate per-key initialization. A mapping function should be short and must follow the API restrictions; slow network work inside it can delay other updates and complicate failure behavior. Thread safety of the map also does not make a mutable value thread-safe. A shared ArrayList stored in the map still needs its own protection or replacement with an immutable value. Iterators are weakly consistent, so a traversal during updates is not an all-entries transaction snapshot. size is useful for observations but not as an admission-control check that must remain true while other threads modify entries. For multi-key invariants, introduce an explicit higher-level coordination design.

中文：ConcurrentHashMap 的单个方法安全，不代表 containsKey 与 put 的组合原子化；检查之后可能有其他线程插入。可用 putIfAbsent 安装现成值，或用 computeIfAbsent 做符合约束的按键初始化。映射函数应尽量短，避免在内部执行慢网络请求。Map 中的可变对象仍需要自己的同步机制。迭代器是弱一致的，并发遍历不是整个 Map 的事务快照；size 也不能直接当作严格的容量准入判断。跨多个键的不变量需要额外协调。

## Semaphores and permit ownership

Release a semaphore permit only after that request successfully acquired it. A semaphore counts available capacity rather than owning an application resource on behalf of a specific thread. Put release in a finally block that is entered only after acquisition succeeds, or track an acquired flag explicitly. Releasing after tryAcquire returned false inflates capacity and silently defeats the limit. A timeout while waiting for a permit should become an overload or deadline result; it should not start the downstream request anyway. Keep the permit until the protected operation actually finishes, including asynchronous completion, otherwise the number of real in-flight requests can exceed the limit. Fairness settings affect acquisition ordering under the documented rules but do not guarantee end-to-end request fairness. A semaphore limits concurrency, not requests per second, and each process-local instance protects only that process unless a separate distributed mechanism is used.

中文：只有成功取得许可的请求才能释放许可。可把 finally 放在 acquire 成功之后，或者显式记录 acquired 标志。tryAcquire 失败后仍 release 会凭空增加容量。异步请求应在真正结束时释放，而不是一返回 Future 就释放。等待许可超时应返回过载或超时，不能绕过限流继续调用。Semaphore 限制的是同时进行的请求数，不是每秒请求数；进程内信号量也不能自动约束整个集群。公平参数不等于端到端绝对公平。

## ThreadLocal context lifecycle

ThreadLocal state follows a worker thread, not the lifetime of an HTTP request. A pool reuses a worker for unrelated requests. If one request stores a user identifier and leaves it behind, a later request on that worker can observe stale identity or tracing context. Establish the context at entry and remove it in finally, including exceptional exits. Avoid letting an empty or anonymous request inherit the previous request value. Moving to another executor does not automatically copy ordinary ThreadLocal values. If a framework provides explicit context propagation, pass a minimal snapshot and restore the receiving thread previous context after execution. Mutable shared context can still race even when its reference was transferred correctly. InheritableThreadLocal concerns thread creation and is not a general solution for pre-existing pools. Never treat a ThreadLocal user identifier as an authorization decision without validating the current request identity and resource permission.

中文：ThreadLocal 的生命周期跟随工作线程，而线程池会复用线程处理其他用户的请求。入口应建立本次上下文，退出时在 finally 中 remove，即使发生异常也要清理。匿名请求不能沿用之前用户的值。跨执行器异步调用不会自动传播普通 ThreadLocal；应使用明确的上下文快照与恢复机制。InheritableThreadLocal 关联线程创建，并不能解决已有线程池的请求传播问题。ThreadLocal 中的用户编号也不能替代当前请求的身份校验和资源授权。

## Safe publication and immutability

Do not let this escape during construction; publish a fully constructed object through a safe channel. Registering a listener from the constructor can expose the partly initialized instance to a callback before all assignments have completed. Starting a thread that reads the instance during construction creates a similar risk. Final fields provide special initialization guarantees only under the relevant Java memory model conditions; they do not repair premature publication. Construct first, then publish through a volatile reference, a synchronized handoff, a concurrent collection, or another documented safe mechanism. A final reference prevents reassignment of that reference but does not freeze the object it points to. If an immutable value contains a list supplied by the caller, make a defensive copy and avoid leaking a mutable internal collection. Safe publication covers visibility of constructed state; protecting later mutation is a separate obligation. Keep construction free from externally visible callbacks whenever practical.

中文：构造期间把 this 注册给监听器或交给新线程，可能让其他代码读到尚未初始化完成的实例。final 字段的初始化保证有前提，不能补救提前泄露。应先完成构造，再通过 volatile 引用、同步交接或并发容器等安全渠道发布。final 引用不等于其指向对象不可变；包含外部列表时应做防御性复制，并避免暴露可变内部集合。安全发布解决初始状态可见性，发布后发生的修改仍需要独立同步。

## Virtual threads and capacity limits

Virtual threads improve the scalability of suitable blocking workloads, not the speed of CPU-bound computation. Many lightweight waiting tasks can be represented without dedicating an operating-system thread to each blocked request. However, only available processor capacity can execute CPU instructions at a given instant. More runnable virtual threads do not multiply cores and can add scheduling work. The database, remote API, memory budget, and open connections remain finite, so protect those resources with explicit limits. A virtual-thread-per-task design is different from pooling virtual threads to cap concurrency: use a semaphore or resource pool for the scarce resource instead. API support and scheduler behavior vary across JDK releases, so check the target runtime rather than assuming every blocking operation scales equally. Measure throughput, tail latency, and resource use with representative workloads. Avoid claims of automatic performance improvement when the bottleneck is a serialized query, external quota, or inefficient algorithm.

中文：虚拟线程主要改善合适阻塞场景的扩展能力，不会让 CPU 密集计算凭空增加核心数。数据库连接、远程接口配额、内存和文件描述符仍然有限，应使用资源池或 Semaphore 单独控制。每任务一个虚拟线程与用线程池限制资源是不同设计，不能把轻量线程当作无限下游容量。具体阻塞行为与 JDK 版本有关，应按目标运行时验证。性能结论需要代表性负载下的吞吐量、尾延迟和资源指标支撑。

## Compare-and-set and ABA

A successful compare-and-set checks the observed value, not the full history of changes. A thread reads reference A and pauses. Another thread changes A to B and later restores A. A compare-and-set from A to C may now succeed even though the first thread reasoning depended on no intervening change. This is the ABA problem. Pair the reference with a version stamp that changes on relevant updates when the history matters, or choose a lock-based design that protects the whole operation. The stamp must be checked and updated atomically with the reference; an unrelated counter does not solve the race. Recycling identifiers or finite counter wraparound also require consideration. ABA is not automatically a bug for every use of compare-and-set: if only the current value matters, the intermediate history may be irrelevant. Retry loops need contention analysis because lock-free progress does not promise fairness or bounded completion time for each thread.

中文：CAS 成功只说明当前值与预期值相等，不能证明期间没有变化。线程看到 A 后暂停，其他线程把 A 改为 B 又恢复为 A，原线程的 CAS 可能成功，但依赖历史未变的推理已经失效。版本戳可以把相关更新历史加入比较，前提是引用与版本一起原子更新，并考虑版本回绕。不是所有 ABA 都会造成错误，关键取决于业务是否关心中间变化。无锁也不代表每个线程都有公平或有界完成时间。

## Primary references

These are original teaching notes with stable concepts, not excerpts from the references. Version-specific behavior must be checked against the deployment JDK.

- [Java memory model, JLS 21 chapter 17](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html)
- [ThreadPoolExecutor, Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)
- [Condition, Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/Condition.html)
- [Semaphore, Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Semaphore.html)
- [CompletableFuture, Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
- [ConcurrentHashMap, Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html)
- [ThreadLocal, Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ThreadLocal.html)
- [AtomicStampedReference, Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicStampedReference.html)
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
