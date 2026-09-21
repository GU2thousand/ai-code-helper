# Dependency failure injection

The automated path uses deterministic test doubles and local HTTP servers. It
requires no Qwen/MCP credentials and adds no public fault-injection endpoint to
the application. Run from the repository root with Java 21 and Maven:

```bash
mvn -f backend/pom.xml \
  -Dtest=PgVectorEmbeddingRepositoryTest,RetrievalServiceTest,HttpCrossEncoderRerankerTest,AgentRuntimeEvaluationTest,BoundedToolRuntimeTest,BoundedToolProviderTest,ProviderFailureIntegrationTest \
  test
```

| Fault | Reproducible injection | Expected evidence |
| --- | --- | --- |
| Vector database unavailable | `PgVectorEmbeddingRepositoryTest` throws a JDBC connection failure; `RetrievalServiceTest.vectorDatabaseFailureFallsBackWithExplicitMetadata` supplies a failing repository. | Connection failure is sanitized, reconnect attempts respect backoff, local index supplies results, and metadata declares `vector_database_unavailable_using_memory`. |
| MCP unavailable / tool timeout | `AgentRuntimeEvaluationTest.allFaultFixturesExerciseTheProductionExecutor`, `BoundedToolRuntimeTest`, and `BoundedToolProviderTest` drive the production executor/provider wrappers with controlled failures, including MCP error results and timeout responses. | Typed `MCP_CONNECTION_FAILED` or `TOOL_TIMEOUT`; bounded attempts/deadlines, cleanup, and no unbounded retry loop. Controlled tool execution does not establish live model tool selection. |
| LLM timeout | `ProviderFailureIntegrationTest` stalls synchronous generation, the first streamed token, and a stream after partial output. | Synchronous timeout has `AI_PROVIDER_TIMEOUT` / HTTP 504; failed stream has a terminal error, failed turn rolls back memory, and conversation/admission leases are released. Late completions cannot commit the failed turn. |
| Reranker timeout | `RetrievalServiceTest.rerankerTimeoutPreservesRrfCandidates`; `HttpCrossEncoderRerankerTest.stalledResponseBodyTimesOutAndCancelsRequest` also sends HTTP headers then stalls the body. | Return the unchanged RRF candidates with `reranker_timeout_using_rrf`; do not relabel RRF as a successful cross-encoder ranking. The HTTP request is cancelled/bounded even after headers have arrived. |

These assertions exercise distinct boundaries. Vector/reranker fallback can
produce a successful answer and commit that successful turn. Memory rollback is
required for a failed generation, rather than every degraded retrieval. Releasing
the HTTP conversation lease does not justify returning a provider slot while an
upstream task still ignores interruption: provider capacity remains bounded until
the underlying task exits or the stream reaches a terminal state. Provider
capacity rejection (`AI_PROVIDER_CAPACITY`, HTTP 503) is an unexpected failure in
the load runners; only HTTP 429 is classified as expected admission saturation.

The PostgreSQL test that requires a real pgvector database is opt-in through
`TEST_PGVECTOR_URL`, `TEST_PGVECTOR_USERNAME`, and `TEST_PGVECTOR_PASSWORD`.
Its result must be reported separately from the offline JDBC failure tests.

For an additional disposable-stack exercise, start with a healthy baseline,
temporarily stop its PostgreSQL container, and send the same RAG workload. Restore
the container and repeat after the configured retry backoff. Keep the raw
request results, retrieval warnings, reconnect timing, and telemetry for both
intervals. The corresponding application settings are:

```properties
app.retrieval.backend=pgvector
app.retrieval.postgres.url=jdbc:postgresql://postgres:5432/aicodehelper
app.retrieval.postgres.connect-timeout-seconds=3
app.retrieval.postgres.query-timeout-seconds=5
app.retrieval.postgres.retry-backoff=30s
```

The reranker adapter is selected with `app.retrieval.mode=hybrid_rerank`,
`app.retrieval.reranker.type=http-cross-encoder`, an explicit local test URL, and
`app.retrieval.reranker.timeout`. Its protocol is
`POST {"query": "...", "documents": ["..."]}` with response
`{"scores": [number, ...]}` in input order. Reuse the HTTP fixture in the test
above for delayed headers/body experiments rather than setting a tiny timeout
against an unrelated external service.

For each manual experiment, retain the injected fault, expected fallback/error,
observed status, memory before/after, active server slots before/after, and recovery
result. The runner verifies HTTP/SSE completion and reports client-observed open
streams; backend tests and metrics establish memory rollback and server slot
release. Record an explicit “not run” when a real dependency was unavailable.
Passing deterministic tests is not evidence of Qwen/MCP availability or production
recovery timing.
