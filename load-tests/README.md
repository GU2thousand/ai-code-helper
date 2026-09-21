# Load and failure testing

These runners measure the current HTTP server and its configured dependencies. A
`local-mock` run measures application overhead and admission controls; it does not
measure Qwen latency, answer quality, or MCP success. No production capacity
numbers are supplied with these scripts.

## Repeatable setup

Use Python 3.10+ for streaming and k6 **2.2.0** for HTTP workloads. The k6 script
has been checked with that version. Native binaries and checksums are available
from the [official release](https://github.com/grafana/k6/releases/tag/v2.2.0).
No k6 extension or Python package is required.

Start the backend using the repository's local instructions, then run from the
repository root. All defaults target `http://127.0.0.1:8081`.

```bash
mkdir -p load-tests/results
curl --fail http://127.0.0.1:8081/api/health > load-tests/results/health.json
git rev-parse HEAD > load-tests/results/commit.txt
k6 version > load-tests/results/k6-version.txt
python3 --version > load-tests/results/python-version.txt
```

Record CPU, memory, OS, Java version, model/embedding names, retrieval mode and
backend, corpus version, and all admission/provider limits beside every report.
Do not include keys or guest cookies. A local uncommitted source revision should
be recorded as such. The ignored `results/` directory is for raw measurements;
only promote a report into a published benchmark after checking its context.

## HTTP: 10, 50, and 100 virtual users

```bash
SCENARIO=chat10 SUMMARY_PATH=load-tests/results/chat10.json k6 run load-tests/http.js
SCENARIO=chat50 SUMMARY_PATH=load-tests/results/chat50.json k6 run load-tests/http.js
SCENARIO=chat100 SUMMARY_PATH=load-tests/results/chat100.json k6 run load-tests/http.js
SCENARIO=rag SUMMARY_PATH=load-tests/results/rag.json k6 run load-tests/http.js
```

Restart the **disposable test instance** or wait for the rate-limit window to
clear between runs. Defaults use 30-second constant-VU scenarios. This is a closed
workload: slower requests reduce offered throughput. VUs include think time, so
100 VUs is not proof of 100 simultaneous admitted model requests. Use the server's
in-flight metrics to confirm actual concurrency.

Every VU creates its own signed guest cookie and keeps it across iterations;
every AI request gets a unique conversation ID. The default 3.1-second think time
avoids exceeding the default 20 starts/minute per-owner limit in a steady run.
The global defaults still enforce 300 starts/minute and 64 concurrent AI requests.
Provider-specific capacity may be lower. Consequently, 50/100 VUs can deliberately
show saturation at the default settings.

For a separate capacity experiment on an isolated instance, explicitly record
and adjust `AI_MAX_STARTS_PER_MINUTE`,
`AI_MAX_STARTS_PER_MINUTE_PER_OWNER`, and `AI_MAX_CONCURRENT_REQUESTS`, plus the
provider concurrency/timeout settings. Never interpret a limit change as a
performance improvement. Keep enough conversation capacity for fresh memory IDs
or record evictions; persistent storage costs are part of the measured workload.

Useful environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `BASE_URL` | `http://127.0.0.1:8081` | Backend origin |
| `SCENARIO` | `chat10` | `chat10`, `chat50`, `chat100`, `rag`, `tools` |
| `DURATION` | `30s` | Time to launch iterations |
| `THINK_SECONDS` | `3.1` | Pause after each AI attempt |
| `REQUEST_TIMEOUT` | `120s` | Per HTTP request timeout |
| `GRACEFUL_STOP` | `130s` | Time to drain in-flight iterations |
| `MAX_SATURATION_RATE` | `1` | Maximum allowed fraction of HTTP 429 responses |
| `SUMMARY_PATH` | `summary-SCENARIO.json` | Raw JSON summary |

For example, set `MAX_SATURATION_RATE=0` for an unsaturated baseline acceptance
check. Every run requires at least one successful AI response, even when 429 is
expected. HTTP 503, transport errors, malformed JSON, wrong memory IDs, and empty
answers are unexpected failures. The default unexpected-error thresholds are
below 1%; latency targets must be chosen for the actual deployment before using
these results as an SLO gate.

The JSON summary contains p50/p95/p99 for successful AI response duration,
`ai_attempts` and `ai_successes` count/rate, `ai_saturation_429`, saturation rate,
and unexpected error rates. `ai_non_saturated_error_rate` excludes 429 from its
denominator, preventing a mostly rejected run from hiding errors in admitted
requests. Setup and guest HTTP calls are excluded from these custom AI metrics.
`failure_classification` adds status and error-code counts for failed AI attempts,
including 429 rejections. Codes use a fixed application allowlist; malformed,
missing, oversized, and unknown error payloads use fixed buckets. Known statuses
are exact; other statuses use bounded `other_Nxx` buckets (`0` means a transport
failure). `AI_PROVIDER_CAPACITY` and `AI_PROVIDER_QUEUE_TIMEOUT` at HTTP 503
remain unexpected failures. These diagnostics do not change the acceptance
thresholds or retain response text.
HTTP response duration and `http_req_waiting` are **not** first-token latency.

These choices use k6's [constant-VU scenarios](https://grafana.com/docs/k6/latest/using-k6/scenarios/),
[per-VU execution identity](https://grafana.com/docs/k6/latest/javascript-api/k6-execution/),
and [persistent cookie jars and percentile options](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/).

## Tool workload

```bash
SCENARIO=tools SUMMARY_PATH=load-tests/results/tools.json k6 run load-tests/http.js
```

This sends tool-oriented prompts through the normal chat API. It deliberately
refuses a `local-mock` model or unconfigured MCP. A completed answer still does
not prove that a model selected the intended tool: verify actual tool invocation,
failure, and timeout counters/traces for the run. A server with no actual tool
calls must be labeled “tool execution not observed,” regardless of HTTP success.
The user must configure providers separately before this external-service test;
the offline test suite does not silently enable them.

## Real SSE streaming

```bash
python3 load-tests/sse_load.py --base-url http://127.0.0.1:8081 \
  --concurrency 10 --duration 30 --timeout 120 \
  --output load-tests/results/sse10.json
```

`sse_load.py` creates one guest cookie jar per worker, creates a single-use stream
ticket for each fresh conversation, then reads the actual streaming response
incrementally. It measures:

- GET-to-first-content and ticket-POST-to-first-content p50/p95/p99.
- Request and stream duration, plus successful request duration separately.
- Completed streams/second and received content events/second.
- Maximum simultaneously open streams, final active streams, HTTP status counts,
  error classifications, allowlisted error-code counts, and 429 counts by ticket/stream phase.

`requests.error_code_counts` extracts known application codes from bounded HTTP
error JSON bodies and SSE `error` events (plain code or JSON `code` field).
`workers.error_code_counts` reports guest setup errors separately. Diagnostic
HTTP reads share the request deadline and are capped at 16 KiB. Unknown or
malformed payloads use fixed buckets, and response messages, prompts, answers,
and cookies are never copied into the report. SSE error events remain failed
requests even when they contain a capacity code; only an actual HTTP 429 counts
as saturation.

Only the first nonempty `message` event counts as first content. Headers, `meta`,
and `sources` do not. Content events are not model tokens. A successful stream
must produce content and the explicit `done: [DONE]` event; `error`, malformed
content, premature EOF, and a deadline all fail. The absolute timeout covers
ticket creation and reading, including peers that keep a connection alive by
trickling bytes. The launch duration includes guest setup; existing streams drain
after it ends. `streams.max_active` reports observed open responses, not a claim
that every configured worker was simultaneously admitted.

The runner supports `--max-requests` for a bounded smoke test and
`--think-seconds` for pacing. Its default pacing is also 3.1 seconds. It reports
429 separately, exits nonzero on setup/unexpected failures or zero successful
streams, and supports `--max-saturation-rate` for an optional admission target.
Milestone latency distributions include attempts that reached the milestone even
if they failed later; use successful duration for completed-request comparisons.

## Resource usage

Capture server CPU, resident memory, JVM heap/GC, thread count, and open/active
streams over the same interval, using Prometheus/Grafana from the observability
stack. A percentile or one snapshot cannot replace a time series. For a Compose
deployment, a separate terminal can retain container samples:

```bash
docker stats --format '{{json .}}' > load-tests/results/container-resources.jsonl
```

Stop that command after the workload and record the sampling window. Also record
load-generator CPU/memory: a saturated generator can understate server capacity.
The Python client reports client-observed streams; use backend metrics for server
execution slots and lingering work after disconnects.

## Runner verification

```bash
k6 inspect load-tests/http.js
python3 -m unittest discover -s load-tests -p 'test_*.py' -v
```

The k6 client tests require `k6` on `PATH` or `K6_BINARY=/absolute/path/to/k6`;
they are explicitly skipped if absent. They run real k6 against an isolated local
HTTP fixture to verify guest-cookie separation, unique conversation IDs, failure
thresholds, and the local-mock tool guard. The SSE tests use delayed local HTTP
frames to verify first-content timing independently of stream completion and
failure handling. Both suites check provider capacity/queue-timeout codes and
ensure unknown or malformed payloads cannot inject response text into reports;
SSE tests also check bounded diagnostic reads. Fixture tests validate the runners; they are not backend or
provider load-test results.

## Dependency failures

See [failure-injection.md](failure-injection.md) for the deterministic vector,
MCP, model-timeout, and reranker-timeout fault matrix and acceptance checks.
Keep fault experiments separate from latency baselines.
