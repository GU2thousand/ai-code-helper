# Bounded provider queue: paired local measurements

Measured 2026-09-21 UTC on clean source [`b4cabf9`](https://github.com/GU2thousand/ai-code-helper/commit/b4cabf91210470546a5dc73cf47ab216383078c7). The same backend/frontend image IDs and JAR served both profiles. [Manifest](manifest.json) records actual bound configuration, source/image/JAR identity, host, tool versions, script hashes, commands and cleanup. [Machine-readable comparison](comparison.json) retains the original failure classifications.

Only `AI_PROVIDER_MAX_QUEUED` changed: **0 off, 48 on**, with a **2-second queue ceiling**. Physical provider capacity remained **16**, application admission **64**, global starts **300/minute**, owner concurrency **2**, owner starts **20/minute**. Local mock chat, local hash embeddings, BM25/pgvector, persistence and 100% tracing were enabled for both profiles. Real Qwen/MCP was not configured.

All seven queue-on samples passed the unchanged client thresholds, with zero unexpected failures. Queue-off Chat50/100 and SSE50/100 failed, entirely with `AI_PROVIDER_CAPACITY`. Both 100-user profiles still report application HTTP429 separately; it is never counted as success. Queueing trades rejection for waiting: SSE50 success p95 increased **1322.0 to 1636.5ms**, and SSE100 **1677.7 to 2073.8ms**. SSE100 success increased from **173/300 to 264/300**, retaining **36 HTTP429s**.

| Workload | Queue | Success / attempts | 429 | Unexpected | Success p95 ms | TTFT p95 ms | Provider / queue peak | End zero | Exit |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|
| chat10 | off | 30/30 | 0 | 0 | 467.2 | — | 10 / 0 | yes | 0 |
| chat10 | on | 30/30 | 0 | 0 | 858.3 | — | 0 / 0 | yes | 0 |
| chat50 | off | 145/150 | 0 | 5 | 1002.4 | — | 2 / 0 | yes | 99 |
| chat50 | on | 150/150 | 0 | 0 | 947.2 | — | 9 / 0 | yes | 0 |
| chat100 | off | 218/300 | 36 | 46 | 1202.0 | — | 2 / 0 | yes | 99 |
| chat100 | on | 278/314 | 36 | 0 | 1190.5 | — | 1 / 0 | yes | 0 |
| rag | off | 31/31 | 0 | 0 | 435.4 | — | 10 / 0 | yes | 0 |
| rag | on | 40/40 | 0 | 0 | 366.2 | — | 0 / 0 | yes | 0 |
| sse10 | off | 32/32 | 0 | 0 | 516.0 | 323.1 | 4 / 0 | yes | 0 |
| sse10 | on | 31/31 | 0 | 0 | 489.0 | 291.1 | 2 / 0 | yes | 0 |
| sse50 | off | 93/150 | 0 | 57 | 1322.0 | 1154.7 | 16 / 0 | yes | 1 |
| sse50 | on | 150/150 | 0 | 0 | 1636.5 | 1491.4 | 16 / 28 | yes | 0 |
| sse100 | off | 173/300 | 36 | 91 | 1677.7 | 1434.4 | 16 / 0 | yes | 1 |
| sse100 | on | 264/300 | 36 | 0 | 2073.8 | 1862.5 | 16 / 42 | yes | 0 |

Failure code counts (raw client classification):

- off/chat50: AI_PROVIDER_CAPACITY=5
- off/chat100: AI_CAPACITY_REACHED=36, AI_PROVIDER_CAPACITY=46
- on/chat100: AI_CAPACITY_REACHED=36
- off/sse50: AI_PROVIDER_CAPACITY=57
- off/sse100: AI_CAPACITY_REACHED=36, AI_PROVIDER_CAPACITY=91
- on/sse100: AI_CAPACITY_REACHED=36

## Method and limits

- Fourteen samples, each with a 10-second launch window and 3.1-second think time; HTTP uses k6 2.2.0 and SSE uses the incremental Python client. Fresh owned volumes per profile, same scenario order, and backend restart before every sample reset the admission window.
- The protected status preflight initializes the retrieval index before each sample. There is no chat/SSE warmup; request-path JIT and host variation remain. These timings must not be compared directly with historical `local-v1`, which had a different preflight.
- This is a closed workload: waiting changes the offered request count (for example, Chat100 is 300 vs 314 attempts). These single, fixed-order, short samples demonstrate local burst handling; they do not establish statistical latency gains, sustained throughput, production SLOs, or capacity of a real model.
- Both final corpora match: 11 documents, 272 chunks, identical corpus/model/version. All 14 samples drained physical calls, queued calls and SSE gauges to zero. Observed provider peak was 16, observed queue peak 42; finite sampling is a lower bound, not proof of an exact maximum. The 12 deterministic admission regressions test the hard bounds and cancellation contracts.
- Resource sampling targets 100 ms; actual cadence/gaps are preserved. Resource windows include setup/drain; successful AI latency excludes setup. CPU is core-based (100% = one core); container CPU includes the lightweight observer. Java RSS, cgroup charged memory and client RSS have different meanings. Client `ps` terminal-zero rows can yield negative CPU deltas; do not derive aggregate CPU from them. Raw warning logs and Python 3.14.4 are retained; the same observer ran for both profiles.
- Queue-on `exit=0` means the existing thresholds passed, which allow explicitly reported 429 saturation. Manifest `complete=true` means the experiment finished; it does not turn queue-off failures into passes.

## Validation and artifacts

[Implementation CI](implementation-ci.json): all 7 jobs passed, including Java 21, 46 frontend tests/build/audit, 22 evaluation tests, 16 load-client tests, browser runtime, PostgreSQL and container validation. [Local backend suites](backend-verification.json):169 passed, 2 database-gated cases skipped locally; the separate CI PostgreSQL suite passed 10/10 with no skips and covers both gated cases. The 12 new admission cases cover FIFO, full queue, queue/model deadlines, queued and active cancellation, shutdown, synchronous tool-round reentry and physical-slot retention.

[Post-load Compose smoke](on/compose-smoke.json) passed all 11 checks, including 14 Grafana panels, Prometheus queries and stored request/retrieval traces. An optional direct `provider.queue` Tempo search happened after cleanup and could not connect; [its record](provider-queue-trace-summary.json) is retained, so this run does not independently claim that specific span was retrieved. Queue metrics and queue-wait counters are present in the measured Prometheus artifacts. Both disposable stacks stopped successfully; named evidence volumes were retained.

Each profile retains original client JSON/logs, actual health/runtime configuration, before/after Prometheus data, timestamped backend/client resource samples, gauge/drain summaries and command exits. `run_paired.py` and `summarize.py` archive the exact host-specific orchestration and summarizer; its paths/layout describe this measured Mac workspace. For portable reruns, use the commands in [the load guide](../../README.md) with the recorded duration, pacing and limits, and a Compose environment override as described in [deployment](../../../deployment/README.md). Keep both queue profiles on identical images and actual bound settings.

## 中文说明

同一干净提交和镜像，只切换队列容量0/48；模型物理并发仍为16，应用并发64、每分钟300次等限制不变。开启队列的7个样本均无意外失败；SSE50从93/150成功变为150/150，SSE100从173/300变为264/300，仍有36次应用层429。代价是SSE成功请求p95分别增加约0.31秒和0.40秒。14个样本结束时执行槽、队列和活跃流均归零。

每个样本仅10秒，使用本地模型，且诊断预检会加载索引；没有真实Qwen/MCP调用，不能据此宣称生产100并发或统计显著的延迟提升。保留失败样本、完整分母、原始退出码、采样间隔和资源数据。HTTP503与SSE错误仍算失败，未修改验收阈值。
