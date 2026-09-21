# Java foundations / Java 核心基础

Original bilingual learning notes for Java 21. Each section explains a separate failure mode and its design implications. Examples are teaching examples, not evidence of this project's runtime behavior.

## Java equality and stable hash keys

Hash-based collections require equal keys to have equal hash codes, and key identity must stay stable while stored. A HashMap first narrows candidates with the hash and then checks equality; an identity-based default equals method will not treat two separately constructed value objects as the same logical key. Implement equals and hashCode together, using the same business identity fields. Prefer immutable identifiers as keys. If a field used by either method changes after insertion, a later get may search a different bucket even when passed the same object. Remove and reinsert only with careful control of the mutation; creating a new immutable key is usually clearer. HashMap also does not supply synchronization for concurrent updates.

哈希集合要求相等对象具有相同哈希值，并且入表后参与相等性判断的键字段保持稳定。只重写 equals 而不重写 hashCode，会导致逻辑相同的对象无法正常查找。比如订单编号作为键，应使用不可变编号，而不是把可修改的订单状态算进哈希值。修改键后 get 可能找不到原来的记录；重新插入应使用新的不可变键。HashMap 本身也不保证并发修改安全。

## Java generic producers and consumers

A producer parameter uses extends to read values; a consumer parameter uses super to accept values. For example, a summation function can accept List<? extends Number> and read Number values from a list of integers or decimals. It cannot safely add an arbitrary Number, because the actual list might be List<Integer>. A copy destination declared List<? super Integer> can accept Integer values, but reads provide only Object without additional knowledge. This is the PECS guideline: producer extends, consumer super. List<Integer> is not a subtype of List<Number>. An extends wildcard is not a guarantee of immutable storage: operations such as clear may still be available. Use an unmodifiable copy when mutation must be prevented.

泛型中的 PECS 表示生产者用 extends，消费者用 super。求和函数从 List<? extends Number> 读取 Number，但不能随意写入 Double，因为底层可能是 List<Integer>。目标容器 List<? super Integer> 可以加入 Integer，读取时只保证得到 Object。List<Integer> 不能直接当作 List<Number>。extends 限制某些写入操作，却不等于不可变集合；需要不可变接口时，应另外创建不可修改的副本。

## Java resource closure and suppressed exceptions

Try-with-resources closes successfully initialized resources in reverse declaration order. Resources must implement AutoCloseable; the compiler arranges cleanup on both normal return and exceptional exit. If a query operation throws and closing its connection also fails, the operation exception remains the primary failure and the closing failure is attached as a suppressed exception. Inspect getSuppressed when diagnosing cleanup problems instead of discarding this evidence. A resource whose initialization failed is not closed by that declaration, while earlier successfully initialized resources are still closed. This structure is preferable to a hand-written finally block that can overwrite the original exception. Closing a stream or connection is resource management, independent of eventual garbage collection of the Java object.

try-with-resources 按声明的逆序关闭成功初始化的资源。资源需实现 AutoCloseable。业务操作与 close 同时失败时，原始业务异常仍是主异常，关闭异常被保存在 suppressed exceptions 中，可通过 getSuppressed 查看。初始化失败的资源不会由该声明关闭，但之前已成功创建的资源仍会清理。不要依靠垃圾回收及时释放数据库连接或文件句柄，也不要在 finally 中用关闭异常覆盖真正的根因。

## Java stream laziness and side effects

Stream intermediate operations are lazy, and a terminal operation drives the computation. Constructing users.stream().filter(...) does not by itself visit users. A short-circuiting terminal operation may examine only part of the source, and optimizations may omit computations that do not affect the result. Therefore peek is unsuitable for required business side effects such as charging an account. Prefer a clear imperative operation for mandatory effects, or collect a computed result and then process it explicitly. Stream behavioral functions should avoid changing the source and avoid shared mutable state, especially with parallel streams. A consumed stream cannot be reused; create another stream from the collection for another traversal. Laziness is an execution property, not an automatic performance improvement.

Stream 的中间操作具有惰性，只有终止操作才驱动执行。filter 本身不遍历数据；findFirst 等短路操作可能只访问部分元素，因此不能依赖 peek 完成必须执行的业务写入。函数应避免修改数据源，也不要让并行流共享一个可变 ArrayList 来收集结果，应使用合适的 collect。流消费一次后不能重复使用，需要重新从集合创建。性能收益仍要测量，不能只因用了 parallelStream 就断言更快。

## Java String code units and code points

String.length counts UTF-16 code units, so one supplementary Unicode code point can occupy two positions. For example, an emoji outside the basic multilingual plane is represented by a surrogate pair; charAt returns one code unit, not necessarily a whole Unicode character. Use codePoints or codePointCount for operations defined in terms of code points, and offsetByCodePoints when calculating substring boundaries by code-point count. Even a code point is not always a user-perceived character: combining marks and multi-code-point emoji can form a single grapheme cluster. Define a field limit explicitly as bytes, UTF-16 units, code points, or grapheme clusters. Encode network text with a specified charset such as UTF-8, and never truncate an encoded byte sequence blindly in the middle of a character.

String.length 统计 UTF-16 代码单元，不等于用户看到的字符数量。补充平面中的 emoji 使用代理对，占两个 char；charAt 可能只取得一半。按码点处理可使用 codePoints、codePointCount 或 offsetByCodePoints；组合附加符和复合 emoji 仍可能由多个码点组成一个字素簇。输入长度限制应明确单位是字节、代码单元、码点还是字素簇。网络编码需指定 UTF-8 等字符集，截断时不能破坏完整编码序列。

## Java generic erasure and runtime type checks

Type erasure means a generic instance does not usually carry its element type as a runtime class distinction. An ArrayList<String> and an ArrayList<Integer> have the same runtime class, so instanceof List<String> is not a valid general runtime check. The compiler enforces generic constraints, inserts necessary casts, and can generate bridge methods to preserve overriding after erasure. Raw types bypass useful checks and may postpone a type error until an unrelated read performs a cast. Use List<?> when the element type is unknown, then validate individual elements if required. Reflection can expose generic declarations on fields or methods, but that does not mean an arbitrary list instance knows its contents' declared type. Deserializers therefore need explicit type information for nested generic targets.

泛型擦除使 ArrayList<String> 与 ArrayList<Integer> 在运行时具有相同的 Class，不能用 instanceof List<String> 普遍检查元素类型。编译器负责类型检查，必要时插入强制转换，并通过桥接方法维护覆盖关系。原始类型会跳过检查，直到后续读取才可能抛出 ClassCastException。未知元素类型宜用 List<?>，需要时逐项验证。反射可以读到字段或方法声明上的泛型签名，但这不表示任意集合实例保存了元素类型；反序列化嵌套泛型需要额外传入类型信息。

## Java time instants zones and daylight saving

An Instant identifies a point on the timeline, while a LocalDateTime has no offset or time zone. Use an Instant for a recorded event timestamp and retain a ZoneId when a business rule refers to local civil time, such as opening at nine each morning. Converting a LocalDateTime to a ZonedDateTime must account for daylight-saving gaps and overlaps: a local clock time may correspond to no instant or two possible instants. Decide the policy explicitly instead of assuming every local day is exactly twenty-four hours. Date-based additions such as plusDays preserve a local-calendar intention, whereas adding a twenty-four-hour duration follows elapsed time. Inject a Clock into date-sensitive services so boundary conditions can be tested without changing the machine clock.

Instant 表示时间线上的一个时刻，LocalDateTime 本身没有时区或偏移量。记录已发生事件可保存 Instant；每天当地九点开门等规则还需要 ZoneId。夏令时切换可能让某个本地时间不存在，或对应两个时刻，应明确处理策略。按日历增加一天与增加固定二十四小时不一定相同。与当前时间有关的服务可注入 Clock，以便测试日期边界，不要依赖更改机器时钟。

## Java future deadlines and cancellation

CompletableFuture.orTimeout completes the future exceptionally without guaranteeing that its underlying work stops. A timeout changes the result observed by dependents; a blocking HTTP call may continue occupying a socket or worker unless its client has a deadline or explicit cancellation support. CompletableFuture.cancel also represents exceptional completion and does not use interruption to control an underlying computation. Design cancellation across layers: carry a request deadline, configure transport timeouts, cancel supported requests, and release permits in finally. Prefer an explicit executor for blocking tasks so they do not monopolize the common pool used by unrelated asynchronous work. Handle timeout failures separately from valid empty results, because silently returning an empty answer hides the reason for failure.

CompletableFuture.orTimeout 让 future 超时失败，却不保证底层工作已经停止。HTTP 调用可能仍占用连接，因此还要设置客户端超时、传递总截止时间，并调用客户端支持的取消接口。CompletableFuture.cancel 也不能当作强制中断正在运行的计算。阻塞任务宜使用明确配置的执行器；资源许可要在 finally 中释放。超时应保留失败原因，不要被转换成看似成功的空结果，否则调用方无法决定是否重试。

## Java record shallow immutability

A record freezes its component references, not the mutable objects those references point to. For record Team(List<String> members), callers can otherwise change the record's apparent content by modifying the original list or the list returned by the accessor. In a compact constructor, assign members = List.copyOf(members) to obtain an unmodifiable snapshot; the copy still shares element references, so mutable elements need their own policy. Validate invariants in the constructor because every valid record instance should satisfy them. Arrays require particular care: copying on input alone still exposes the stored array through a generated accessor. Choose an immutable component type or also return a defensive copy. Records are concise value carriers, not a universal guarantee of deep immutability.

record 的组件引用不能重新赋值，但引用的集合或数组仍可能可变。Team(List<String> members) 可在紧凑构造器中用 List.copyOf 保存不可修改的快照，并同时校验必要条件。这个副本仍共享元素引用，所以元素本身可变时也需要策略。数组只在入参时复制还不够，默认访问器会暴露内部数组，需要返回防御性副本或换用不可变类型。record 是浅层不可变的数据载体，不自动实现深层不可变。

## Java Optional eager and lazy fallbacks

Optional.orElse evaluates its argument before the method call, while orElseGet invokes a supplier only for an empty Optional. Therefore cached.orElse(loadFromDatabase()) still calls the database even when cached contains a value. Use cached.orElseGet(this::loadFromDatabase) when fallback computation is expensive or has effects. Neither choice makes the fallback non-null automatically; the supplier's return contract still matters. Optional is useful for an explicit absence in return values, but repeatedly calling get without checking throws away that signal. Prefer map, flatMap, or orElseThrow when those operations express the intended transformation or required value. A fallback that writes data should be named and tested so a future refactor does not unexpectedly execute it on the successful path.

orElse 的参数会先求值，而 orElseGet 只在 Optional 为空时调用 Supplier。因此即使命中缓存，cached.orElse(loadFromDatabase()) 仍会查库；昂贵操作可改用 orElseGet。Supplier 仍可能返回 null，需要另行约定。Optional 用于明确表达缺失，不应不检查就反复 get。根据意图使用 map、flatMap 或 orElseThrow，并特别测试带写入副作用的回退逻辑，避免正常路径也执行回退。

## Java BigDecimal construction and scale

Create decimal business values from decimal text or another exact representation, not an already rounded binary floating-point value. For example, new BigDecimal("0.1") preserves the intended decimal, while new BigDecimal(0.1) captures the exact binary double approximation. BigDecimal.valueOf(double) uses the double's string representation and is often less surprising, but cannot recover precision lost earlier in a calculation. Equality also needs a policy: equals compares value and scale, so 2.0 and 2.00 are not equal under equals, while compareTo reports numerical equality. Specify a rounding mode and target scale for operations that require rounding, such as non-terminating division. Store the monetary currency alongside the amount; decimal arithmetic alone does not define currency or rounding business rules.

业务小数应从十进制字符串等精确表示创建，不要先经过 double 再期望恢复原始精度。new BigDecimal("0.1") 保留十进制意图；new BigDecimal(0.1) 会保存二进制近似值。equals 同时比较数值和 scale，因此 2.0 与 2.00 不相等；compareTo 比较数值时则为零。除法或金额舍入应明确指定舍入模式与小数位，并保留币种信息。valueOf 不能找回之前计算已经丢失的精度。

## Java class initialization and constants

Class loading and class initialization are separate events, and merely referencing a compile-time constant need not initialize its declaring class. Before active use such as creating an instance or invoking a static method, initialization runs the required superclass initialization and then the class's static field initializers and static blocks in textual order. Only the class that actually declares a referenced static field is initialized by that field access, not necessarily the subclass name used to refer to it. Avoid large network operations or fallible configuration discovery in static initialization because failures can leave the class unusable for later active uses. Prefer explicit construction for dependencies and startup checks. A final field is not automatically a compile-time constant; the constant-expression rules determine that distinction.

类加载和类初始化是不同阶段，引用编译期常量不一定触发其声明类的初始化。创建实例或调用静态方法等主动使用前，要先完成所需父类初始化，再按源码顺序执行本类静态字段初始化器与 static 块。通过子类名称引用父类声明的静态字段，通常初始化实际声明该字段的父类，而不是子类。不要在静态初始化中执行大规模网络请求；失败可能让后续使用持续报错。final 字段不一定是编译期常量，还要满足常量表达式规则。

## Java map iteration order and access order

Choose a map implementation from the required iteration contract instead of relying on observed HashMap order. LinkedHashMap normally preserves insertion encounter order, so inserting an existing key again does not move it to the end in that mode. An access-order LinkedHashMap instead updates encounter order on operations such as get and put, which can support a simple least-recently-used eviction policy through removeEldestEntry. That mode means even a successful lookup can alter iteration order; it should not be treated as an immutable read when coordinating iteration. For sorted keys, a TreeMap supplies comparator or natural ordering rather than insertion ordering. None of these ordering choices by itself supplies a complete production cache with expiration, capacity accounting, or safe concurrent access.

不能依靠观察到的 HashMap 遍历顺序作为接口契约。LinkedHashMap 默认按插入顺序遍历，重复 put 已有键不会移动位置；访问顺序模式则会因 get、put 等操作改变顺序，可结合 removeEldestEntry 实现简单 LRU 淘汰。此时成功读取也可能改变遍历结构。若要求键排序，应使用 TreeMap 的自然顺序或比较器。迭代顺序不自动提供过期、容量统计和并发安全等完整缓存能力。

---

Primary references (Java 21 unless marked as a language tutorial):

- [HashMap API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/HashMap.html)
- [Generic wildcard guidelines](https://docs.oracle.com/javase/tutorial/java/generics/wildcardGuidelines.html)
- [Try-with-resources](https://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html)
- [Stream package contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/package-summary.html)
- [CompletableFuture API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
- [Record API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Record.html)
- [Optional API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html)
- [BigDecimal API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html)
- [String code units and code points](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html)
- [Generic type erasure](https://docs.oracle.com/javase/tutorial/java/generics/erasure.html)
- [ZonedDateTime and time-line arithmetic](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html)
- [Java Language Specification: execution and initialization](https://docs.oracle.com/javase/specs/jls/se21/html/jls-12.html)
- [LinkedHashMap iteration contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/LinkedHashMap.html)
