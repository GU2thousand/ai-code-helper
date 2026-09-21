# Database Foundations for Application Engineers

Original bilingual study notes with worked scenarios. SQL examples are illustrative PostgreSQL patterns, not changes to this project's storage configuration. Each section names a distinct concept for retrieval evaluation; reference links support factual checks rather than promise production readiness.

## ACID and Business Invariants

ACID describes atomicity, consistency, isolation, and durability. Atomicity makes a transaction's database changes succeed or roll back together. Consistency means preserving declared constraints and business invariants; the database cannot infer a rule that nobody encoded. Isolation controls interactions among concurrent transactions, with guarantees depending on the selected level. Durability concerns committed data surviving failures under the configured persistence policy. Consider a ticket reservation: inserting a reservation and decrementing available capacity belong in one transaction, but preventing negative capacity still requires a suitable constraint or conditional update. **A transaction boundary does not invent the business invariant.** Test both mid-transaction failure and concurrent reservations. Sending a confirmation email is an external effect; use a durable outbox or another explicit delivery design if it must follow the commit.

中文：ACID 分别指原子性、一致性、隔离性、持久性。把预订与库存扣减放进同一事务，可以避免只完成一半；库存不为负仍需要约束或原子条件更新。事务边界不会自动补全业务规则。邮件等外部操作也不会随数据库回滚，必须单独设计提交后的投递与去重。

## Read Committed Statement Snapshots

In PostgreSQL READ COMMITTED, an ordinary SELECT sees committed rows as of that statement's start, plus earlier writes from its own transaction. **Two SELECT statements in one transaction may observe different committed versions.** For example, a dashboard reads the number of pending jobs, another session commits a new job, and the dashboard's later detail query includes that job. This is not a dirty read: both observations can contain committed data. If two results must describe one database view, prefer a single query where practical, or deliberately choose a stronger isolation strategy and its error handling. Diagnose the issue by recording statement order and commit order, not only request timestamps. Do not generalize ordinary SELECT behavior to every locking or updating command; concurrent updates have additional waiting and predicate rechecking rules.

中文：读已提交通常是语句级快照。同一事务的两次查询之间，其他事务提交的数据可能进入第二次结果。统计与明细不一致，并不必然说明读到了未提交数据。需要一致视图时，可合并查询或明确选择更强隔离；排查时记录查询开始与提交的先后顺序。

## Serializable Transaction Retries

Serializable execution aims for committed transactions to have an outcome compatible with some serial ordering. PostgreSQL can reject a transaction with SQLSTATE 40001 when this cannot be maintained. **Retry the whole transaction from a fresh snapshot after a serialization failure.** Retrying only the last UPDATE can reuse decisions made from invalidated reads. In an appointment scheduler, the retry must read availability again before reserving a slot. Use a bounded retry count, jittered backoff, and an overall request deadline; return a clear conflict or temporary failure when the budget runs out. Delay external effects until commit, or make them independently idempotent. A uniqueness violation is not automatically the same failure class as 40001, and blindly retrying every SQL exception can hide invalid input or overload.

中文：可串行化并不代表所有并发事务都能一次成功。遇到序列化失败，应回滚并从新快照重跑整个事务，包括决定是否写入的查询。重试需要次数与总时间上限，并处理邮件、支付等外部副作用。约束错误、语法错误不能一律当成可重试故障。

## Unique Constraints and Check Then Insert Races

A preliminary SELECT can improve an error message, but it cannot safely reserve a value against another session. **A unique constraint is the final arbiter of concurrent duplicate inserts.** For an account's normalized handle, two requests can both see no existing row and then attempt insertion. Encode the intended identity with a unique constraint or unique index, attempt the write, and translate the resulting conflict into a domain response. Where semantics allow it, INSERT ON CONFLICT provides an explicit alternative action; do not silently overwrite an existing account to make the error disappear. Decide case normalization, tenant scope, and NULL behavior before selecting the constraint. PostgreSQL's default unique semantics permit multiple NULL values, so a required identity generally needs NOT NULL as well.

中文：先查再插存在竞态，两个请求都可能查到“不存在”。真正阻止重复的是数据库唯一约束。业务要先定义大小写、租户范围和空值规则，再把冲突转换为可理解的响应。ON CONFLICT 需要明确业务含义，不能为消除报错而覆盖另一个用户的记录。

## Composite B Tree Indexes

For a B-tree index on `(tenant_id, created_at, id)`, a tenant equality filter followed by a creation-time range is a useful access pattern. **Leading equality keys and the first range key shape the scanned index region.** Later conditions can still help, but index usefulness depends on selectivity, ordering, table size, and the actual planner. Avoid the absolute claim that omitting the first column always makes the index unusable: PostgreSQL 18 can sometimes use B-tree skip scan. Do not assume an earlier server version supports that optimization. For an activity feed, compare the real query plan and measured buffers before adding a separate index. Every extra index consumes storage and adds write maintenance, so measure the read benefit against the insert and update workload.

中文：复合 B-tree 索引的列顺序应服务实际筛选与排序。前导列等值条件和第一个范围条件通常决定主要扫描区域。缺少首列条件不等于绝对无法利用索引；PostgreSQL 18 的 skip scan 也有适用条件。应结合版本、选择性与执行计划验证，而不是背诵固定口诀。

## Explain Analyze Execution Safety

Plain EXPLAIN describes the chosen plan; EXPLAIN ANALYZE executes the statement and collects runtime observations. **EXPLAIN ANALYZE executes writes and can trigger side effects.** For an UPDATE experiment, use disposable data or a controlled transaction followed by ROLLBACK, but remember that rollback is not a universal sandbox: sequence advancement and external effects from functions may remain. Even read queries can be expensive or invoke functions with effects. Compare estimated versus actual rows, loops, and buffer activity to locate the source of excessive work. An index scan is not automatically faster than a sequential scan. Record representative parameters, data volume, cache conditions, and database version when comparing plans so that a faster tiny-fixture run is not presented as a production benchmark.

中文：EXPLAIN ANALYZE 会真正执行 SQL。诊断写操作时先使用隔离数据；事务回滚可以撤销普通表修改，但不能假设所有副作用都能撤销。分析行数估计、实际循环与缓冲区访问，并记录数据规模及参数。出现索引扫描并不自动证明查询更快。

## Keyset Pagination with a Stable Cursor

For a newest-first feed, order by non-null `(created_at DESC, id DESC)` and carry both last-seen values in the cursor. The next query can use `WHERE (created_at, id) < (:time, :id) ORDER BY created_at DESC, id DESC LIMIT :size`. **A unique tie breaker makes keyset pagination order deterministic.** Filtering by timestamp alone can skip items that share a timestamp. Pair the query with an appropriate index and retain the same filters across pages. This avoids repeatedly computing a large OFFSET, but it does not freeze a changing dataset: edits to ordering keys can still move rows between pages. Prefer immutable ordering keys for feeds, and use a defined snapshot strategy for exports requiring an exact consistent set. Treat the cursor as input to validate, not as authorization.

中文：游标分页应同时保存排序时间和唯一 ID，避免相同时间的记录被跳过。下一页沿用相同过滤条件，并使用严格小于游标的比较。它减少深分页的跳过成本，但并不天然提供跨页快照；修改排序键仍会影响结果，导出场景需要额外的一致性策略。

## Optimistic Version Updates

Store a version number with an editable row and include the previously read version in the update predicate: `UPDATE draft SET body = :body, version = version + 1 WHERE id = :id AND version = :expected`. **A zero-row versioned update signals a conflict or a missing row.** When two editors start from version 7, only the first successful update should advance that version to 8; the other editor must refresh or explicitly merge changes. Never report success solely because the SQL statement completed without an exception: inspect the affected-row count. Every writer must participate in the version protocol, otherwise an unversioned write can defeat it. A version predicate protects this row's stale update problem, not arbitrary invariants spanning several tables or rows.

中文：乐观锁把读取时的版本加入 UPDATE 条件，并在写入时递增版本。影响行数为零说明版本冲突或记录已不存在，不能当作更新成功。所有写入路径都必须遵守版本协议；单行版本号不能自动保证跨表、跨行的业务一致性。

## Row Locks and Transaction Scope

SELECT FOR UPDATE can serialize competing changes to existing rows, but the lock's useful lifetime is the surrounding transaction. **Acquire the row lock and perform the protected write in the same transaction.** An autocommit SELECT that returns and commits before a later UPDATE leaves the later operation unprotected. For two-wallet transfers, lock wallets in a stable ID order to reduce deadlock opportunities and keep the locked section short. Set a deliberate wait policy and handle deadlocks or lock timeouts as explicit outcomes. Do not hold locks while waiting for a user, an LLM, or an external HTTP service. A query returning no rows does not create a row lock on a future record, so absent-key protection requires a different design such as a unique constraint.

中文：行锁要覆盖读取、业务判断和修改的同一事务。自动提交后再更新，原来的锁已无法保护后续操作。多行加锁尽量采用一致顺序，并缩短持锁时间。查不到记录不会锁住“未来的行”，此类并发插入通常需要唯一约束等机制。

## Connection Pool Timeout Budgets

HikariCP connectionTimeout bounds how long a caller waits to obtain a pooled connection; it is not the execution timeout of the SQL statement. **Connection acquisition, lock waiting, and SQL execution need separate budgets.** For an illustrative two-second request budget, allocate bounded acquisition and query time while reserving time for serialization and the response; these numbers must be tuned to actual latency objectives. Close connections reliably so one failed request does not exhaust the pool. Observe active connections, queued waiters, acquisition latency, query latency, and timeout counts together. Increasing the pool can increase database contention instead of fixing slow queries. If retries exist at several layers, calculate their combined worst-case cost and stop when the request deadline expires rather than multiplying independent timeout limits.

中文：连接池获取超时只限制“等连接”的时间，不限制拿到连接后的 SQL 执行。请求总预算应覆盖等连接、等锁、执行与返回，并包含重试开销。连接必须及时归还；盲目加大连接池可能加重数据库竞争。监控排队、活跃连接及查询耗时，才能定位瓶颈。

## Parameter Binding and SQL Identifiers

Use prepared statements with bound parameters for untrusted data values. For example, bind a search term in `WHERE handle = ?` instead of concatenating it into SQL. **Bound parameters protect values, while dynamic identifiers need an allowlist.** A placeholder does not choose a table name, column name, or ASC/DESC keyword. For user-selected sorting, map a small set of public sort options to fixed SQL fragments maintained by the application; reject unknown options. Keep authorization and tenant filters in the query regardless of how safely values are bound. Binding a LIKE pattern prevents it from becoming SQL syntax, but `%` and `_` still retain pattern meaning unless escaped according to the intended search behavior. Test malicious-looking values as data and separately test rejected structural options.

中文：参数绑定用于数据值，不能把任意表名、列名或排序关键字变成安全的参数。动态排序应使用白名单映射固定 SQL 片段。防注入不等于权限校验；租户条件仍然必需。LIKE 中的通配符属于查询语义，若要字面搜索，还要按规则转义。

## Expand Contract Schema Migrations

An expand-contract rollout separates compatibility from cleanup. Add the new nullable structure first, deploy code that can coexist with the old representation, backfill in bounded batches, verify the migrated data, switch readers and writers, and remove the old structure only after older application versions are retired. **Contract only after old readers and writers no longer depend on the old schema.** For splitting display_name into given_name and family_name, define ambiguous-name handling before the backfill rather than guessing silently. Make backfill progress resumable, account for writes that occur during migration, and monitor locking and replication impact. A tested rollback plan may need compatible code or forward repair; dropping a column and recreating its name does not recover its deleted values.

中文：先扩展、再收缩允许新旧应用在发布窗口内共存。新增结构、兼容写入、分批回填、核对数据、切换读写、最后删除旧结构，应有明确顺序。回填期间的新增与修改也要处理。删除列后的回滚不能只重建同名列，还必须考虑数据是否可恢复。

## Full Text Ranking Versus BM25 and Vectors

PostgreSQL full-text search uses lexemes, tsvector documents, and tsquery conditions. Its built-in ts_rank and ts_rank_cd are specific ranking functions; **PostgreSQL built-in full-text ranking is not automatically BM25.** A BM25 implementation must explicitly supply its scoring model and corpus statistics. pgvector stores embeddings and supports vector-distance search, whose usefulness depends on the embedding model, chunking, filters, and index configuration. Dense vectors can retrieve paraphrases while lexical retrieval helps with exact symbols, but neither guarantees a correct answer. Hybrid retrieval combines candidate sources, often with a rank-based fusion method, then measures relevance on a fixed labeled set. Do not compare raw scores from unrelated rankers as if they shared a scale, and do not claim an accuracy gain merely because a database extension was installed.

中文：PostgreSQL 内置全文排序不能直接称为 BM25；pgvector 提供向量存储和相似度检索，也不自动提高答案准确率。词法检索与向量检索各有适用场景，混合方案要明确融合规则。比较时固定评估集，并区分召回相关片段、回答正确与引用充分这几种能力。

---

Primary references, checked September 2026:

- [PostgreSQL transactions](https://www.postgresql.org/docs/current/tutorial-transactions.html), [transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html), and [constraints](https://www.postgresql.org/docs/current/ddl-constraints.html).
- [PostgreSQL multicolumn indexes](https://www.postgresql.org/docs/current/indexes-multicolumn.html), [EXPLAIN](https://www.postgresql.org/docs/current/sql-explain.html), [LIMIT and OFFSET](https://www.postgresql.org/docs/current/queries-limit.html), and [row comparisons](https://www.postgresql.org/docs/current/functions-comparisons.html).
- [PostgreSQL UPDATE](https://www.postgresql.org/docs/current/sql-update.html) and [explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html).
- [HikariCP configuration](https://github.com/brettwooldridge/HikariCP), [pgJDBC query parameters](https://jdbc.postgresql.org/documentation/query/), and [OWASP SQL injection prevention](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html).
- [Prisma expand-and-contract migration guide](https://www.prisma.io/docs/guides/database/data-migration), used for the compatibility pattern, not its version-specific command syntax.
- [PostgreSQL text-search ranking](https://www.postgresql.org/docs/current/textsearch-controls.html) and [pgvector documentation](https://github.com/pgvector/pgvector).
