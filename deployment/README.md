# Local AI system stack

From the repository root, Docker Compose builds the Java 21 backend and Vue
production frontend and starts PostgreSQL 17 with pgvector, Prometheus, Grafana,
an OpenTelemetry Collector and Tempo:

```bash
docker compose up --build --detach --wait --wait-timeout 240
python3 deployment/smoke.py
```

No provider configuration is needed. The backend uses the `local` profile:
local-mock chat, deterministic local embeddings and disabled external MCP. The
Compose configuration selects the real pgvector repository. Offline model
behavior validates plumbing and repeatability; it does not measure Qwen answer
quality, real embedding quality or external MCP reliability.

| Service | Local URL | Purpose |
| --- | --- | --- |
| Frontend | http://localhost:5173 | Production static assets and same-origin API/SSE proxy |
| Backend | http://localhost:8081/api/health | Provider and knowledge-base status |
| Metrics | http://localhost:8081/actuator/prometheus | Application metrics |
| Prometheus | http://localhost:9090 | Scrape health and PromQL queries |
| Grafana | http://localhost:3000 | Provisioned AI Engineering dashboard and Tempo data source |
| Tempo | http://localhost:3200 | Trace storage/query API; no standalone UI |
| PostgreSQL | `localhost:5433/ai_code_helper` | Persistent indexed document chunks |

Grafana login is `admin` / `local-development-only`. PostgreSQL user is
`ai_code_helper`, with the same default local password. Override
`GRAFANA_PASSWORD` and `POSTGRES_PASSWORD` before first startup if needed.
Every published port binds to `127.0.0.1`; this configuration is for local use.
Port overrides are `FRONTEND_PORT`, `BACKEND_PORT`, `POSTGRES_PORT`,
`PROMETHEUS_PORT`, `GRAFANA_PORT`, and `TEMPO_PORT`. The smoke script honors those
variables (or explicit URL arguments).

## Data and lifecycle

Named volumes retain PostgreSQL chunks, application conversations and the
generated session-signing secret, Prometheus history, Grafana state and Tempo
traces across restarts. Prometheus retention is seven days; Tempo retention is
24 hours. The collector buffers in memory and is not a durable telemetry queue.

```bash
docker compose stop              # Preserve containers and all stored data.
docker compose start             # Resume the existing stack.
docker compose down              # Remove containers; preserve named volumes.
```

`docker compose down --volumes` deletes the stack's stored data. Changing the
PostgreSQL password after database initialization also requires changing the
database user's password, since the image does not reinitialize an existing
volume. Production hosting, public TLS, backup operations and remote deployment
are outside this local stack.

When upgrading an existing pgvector image, update the extension's SQL definitions
in each existing database as well; initialization scripts only run on empty
volumes:

```bash
docker compose exec -T postgres psql -U ai_code_helper -d ai_code_helper -c 'ALTER EXTENSION vector UPDATE;'
```

## Retrieval and evaluation

Compose sets `APP_RETRIEVAL_BACKEND=pgvector` and
`APP_RETRIEVAL_POSTGRES_URL=jdbc:postgresql://postgres:5432/ai_code_helper`, with
`APP_RETRIEVAL_POSTGRES_USERNAME` / `APP_RETRIEVAL_POSTGRES_PASSWORD`. The
`vector` extension is created on a fresh database; the backend owns its chunk
table and indexing. The retrieval implementation documents whether a request
used PostgreSQL or a fallback; a live database alone is not evidence that a
particular query reached it.

Evaluation endpoints remain disabled by default. To run the evaluation scripts
against this isolated stack, set `APP_EVALUATION_ENABLED=true` and a nonempty
`APP_EVALUATION_KEY` before `docker compose up --detach`; supply that value in
the scripts' evaluation-key option or the `X-Evaluation-Key` request header.
See [evaluation documentation](../evaluation/README.md) for the measured modes
and reproducible datasets.

## Metrics, traces and limits

The **AI Engineering · Requests, Retrieval and Tools** Grafana dashboard provides
request rate/failure ratio, request and LLM p50/p95, retrieval p50/p95 and lexical,
vector and reranker p95, tool latency/calls/failures, reported input/output tokens,
agent steps, active SSE streams and RAG no-hit rate. Histograms use bounded
labels; prompts, user IDs and conversation IDs must never become metric labels.
Empty panels before the corresponding activity occurs are expected.

Token counters use provider-reported usage. They are not dollar estimates; an
offline mock or a provider that omits usage cannot establish real token cost.
The dashboard sums tokens across bounded request modes. Agent steps count
selected logical tool attempts; retries remain one step, and discovery or a
hard-limit rejection does not count as a selected execution.
Latency quantiles depend on sample count and bucket resolution. A single smoke
request is a wiring check, not a latency benchmark.

Tracing follows `backend -> OTLP/HTTP collector:4318 -> Tempo:4318`. Grafana's
Tempo data source queries stored traces. All requests are sampled locally
(`MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0`). To inspect an individual trace,
open Grafana Explore, select Tempo, and search by trace ID. The smoke report
includes the RAG request's injected W3C trace ID and verifies that Tempo can
retrieve that same trace with `ai.rag`, retrieval, lexical and LLM spans. With
evaluation enabled, it separately verifies a hybrid trace containing lexical
and vector stages.
Payload text is excluded from the new observability
attributes.
Compose also enables structured JSON application logs; request and trace IDs
correlate those logs with the stored spans.
The Nginx API access log records only generated request ID, method, status and
duration; it forwards that request ID to the backend. API error logs that could
include full legacy query strings or stream ticket URLs are suppressed. Proxy
failures remain visible as status/upstream-status fields in the safe access log.

## Verification and CI

```bash
docker compose config --quiet
docker compose run --rm --no-deps --entrypoint /bin/promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose run --rm --no-deps otel-collector validate --config=/etc/otelcol-contrib/config.yaml
python3 deployment/smoke.py
```

The smoke script exercises the real reverse proxy, cookie session, RAG endpoint,
SSE completion, Prometheus scrape, all dashboard PromQL expressions, stored trace
correlation and Grafana dashboard provisioning. It writes
`output/ai-system/compose-smoke.json`. A failing check exits nonzero and preserves
partial evidence. The `AI system validation` GitHub workflow runs this stack and
the pgvector integration tests against PostgreSQL; it uploads logs and test
reports. Its temporary evaluation key also enables a check that an actual hybrid
query returns `backend=pgvector` with no fallback. The workflow also runs all four
retrieval modes against the fixed QA dataset and the controlled agent runtime
fixtures, preserving their JSON/CSV results. The default local smoke omits
that operator-only check unless `APP_EVALUATION_KEY` is supplied. Normal
backend/frontend tests remain in the existing CI workflow.

Component versions are explicit in `docker-compose.yml` and both Dockerfiles.
For a commit-bound verification, build from a clean checkout with
`SOURCE_REVISION=$(git rev-parse HEAD) docker compose up --build --detach --wait`,
then run `python3 deployment/verification_manifest.py --require-clean`. The
manifest records the source commit, clean-source flag, actual container and image
IDs, image creation timestamps, OCI revision labels and config file hashes. It
does not record environment values. CI builds use the checked-out GitHub SHA and
preserve this manifest alongside the smoke and evaluation results.
The tracing configuration follows the [Tempo collector documentation](https://grafana.com/docs/tempo/latest/set-up-for-tracing/instrument-send/set-up-collector/otel-collector/)
and the [OpenTelemetry Collector configuration model](https://opentelemetry.io/docs/collector/configuration/).
Pinned release references: [pgvector 0.8.6](https://github.com/pgvector/pgvector/tree/v0.8.6),
[Prometheus 3.13.3 LTS](https://github.com/prometheus/prometheus/releases/tag/v3.13.3),
[Grafana 12.4.11](https://github.com/grafana/grafana/releases/tag/v12.4.11),
[Tempo 2.10.8](https://github.com/grafana/tempo/releases/tag/v2.10.8),
and [Collector 0.161.0](https://github.com/open-telemetry/opentelemetry-collector-releases/releases/tag/v0.161.0).


The dashboard also exposes physical provider occupancy, bounded queue length and
queue-wait p95. Admission defaults remain 16 physical calls, with up to 48 queued
calls and a 2-second wait ceiling. Queue time consumes model deadlines; queueing
does not raise real provider capacity or override application admission limits.
Use `AI_PROVIDER_MAX_QUEUED=0` on a disposable test backend for the immediate-reject
control. For Compose overrides, pass these settings in the backend service's
`environment`; setting a shell variable alone does not add it to the container.
