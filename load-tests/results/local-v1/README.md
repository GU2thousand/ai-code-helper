# Local load evidence, version 1

These are actual measurements of the local application, including failed acceptance checks. No real model or MCP credentials were used. Source, image identity, host/runtime and default settings are in [provenance.json](provenance.json). Commands, timing and original exit codes are in [runs.json](runs.json); observed retrieval configuration is in [runtime-status-after.json](runtime-status-after.json).

Seven recorded samples, 2026-09-21 UTC: 10-second launch windows, 3.1s think time, local models, lexical retrieval/pgvector, persistence and tracing enabled. The isolated backend restarted before each sample to reset admission windows; no separate warmup. Limits remained 64 application requests, 16 physical provider calls and 300 starts/minute. Short samples include JVM startup effects.

| Workload / users | Success / attempts | HTTP 429 | Unexpected failures | Success p95 | Success / second |
| --- | ---: | ---: | ---: | ---: | ---: |
| [Chat 10](chat10.json) | 30/30 | 0 | 0 (0.00%) | 599.3 ms | 2.89 |
| [Chat 50](chat50.json) | 150/150 | 0 | 0 (0.00%) | 1294.2 ms | 12.85 |
| [Chat 100](chat100.json) | 238/303 | 36 | 29 (9.57%) | 1262.1 ms | 18.18 |
| [RAG 10](rag.json) | 30/30 | 0 | 0 (0.00%) | 572.9 ms | 2.96 |
| [SSE 10](sse10.json) | 30/30 | 0 | 0 (0.00%) | 689.8 ms | 3.00 |
| [SSE 50](sse50.json) | 88/150 | 0 | 62 (41.33%) | 1225.7 ms | 8.79 |
| [SSE 100](sse100.json) | 146/300 | 36 | 118 (39.33%) | 1750.2 ms | 14.59 |

**Chat 100 and SSE 50/100 failed the load acceptance thresholds.** Chat 100's 29 unexpected failures were HTTP 503. The SSE failures were provider errors after the response opened; their baseline payload codes were not retained. A separate diagnostic run captured 111 `AI_PROVIDER_CAPACITY` SSE errors; it is recorded separately and does not relabel or replace baseline failures. These results expose the configured capacity boundary and do not establish 100-user production readiness. No capacity settings or error classifications were changed to make the runs pass. All SSE runs drained to zero active streams; observed peak open responses were 10, 50 and 63 respectively. Raw TTFT p50/p95/p99, resource samples, status counters and exit codes remain in the linked artifacts.

## Artifacts and interpretation

For each of `chat10`, `chat50`, `chat100`, `rag`, `sse10`, `sse50`, and `sse100`:

- `.json` is the original load-client summary, preserving percentile distributions and error/throughput counters.
- `-resources.jsonl` contains timestamped backend Docker CPU/memory and load-client CPU/RSS samples. Missing/exited client samples remain explicit.
- `-metrics-before.prom` and `-metrics-after.prom` preserve backend counters and active streams around the sample.
- `-health-before.json` records runtime identity after the deliberate backend restart.

The SSE samples separately retain p50/p95/p99 first-content, ticket-to-first-content and successful completion latency. Open HTTP responses are not necessarily admitted physical model calls. The fixed default provider capacity is 16, while application admission permits 64 concurrent requests. Default rate limits remain 300 starts/minute globally and 20 per owner. HTTP 429 is counted separately, and 503/SSE errors remain unexpected failures.

`diagnostic-sse100.json` is one additional, separate sample that records only stable SSE error codes. It observed 111 `AI_PROVIDER_CAPACITY` errors, 36 HTTP 429 responses and 153 successful streams out of 300 attempts. This supports a capacity diagnosis without asserting the unrecorded codes of each original baseline error. It does not replace the original `sse100.json` or change its failure rate.

Rerun commands and methodology are documented in [the load client guide](../../README.md). Default duration is 30s; these recorded short samples explicitly used `DURATION=10s` or `--duration 10`, default 3.1s pacing and a 30s SSE deadline. Separate the baseline from diagnostics and restart only a disposable backend when reproducing the admission-window reset.

## 中文说明

这些是真实本地运行结果，保留了未通过的样本和原始退出码。没有外部模型/MCP调用。10秒短样本、默认并发/限流、无额外预热和每次重启后端的方法都记录在清单中，不能据此宣称生产性能。

Chat100的29个意外失败为HTTP503；SSE50/100的错误归类为`sse_error`，原始样本未保存具体错误码。独立诊断另行捕获111个`AI_PROVIDER_CAPACITY`，没有覆盖原始样本。SSE活跃流最终均归零，但这不意味着高并发成功率通过。语料、实际BM25模式、pgvector和镜像/源码版本均可追溯。
