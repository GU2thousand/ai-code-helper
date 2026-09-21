package com.aicodehelper.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded metrics and explicit, thread-safe trace lifecycles. No prompts, tool
 * arguments, credentials, user IDs or conversation IDs are exported. A Scope
 * must be closed on the thread where it was opened; observations may finish on
 * any thread (in particular a provider callback or servlet timeout thread).
 */
@Component
public final class AiTelemetry {
    private static final Logger log = LoggerFactory.getLogger(AiTelemetry.class);
    private static final Set<String> MODES = Set.of("chat", "rag", "report", "sse");
    private static final Set<String> STATUSES = Set.of("success", "error", "cancelled", "timeout", "rejected");
    private static final Map<String, String> STAGES = Map.ofEntries(
            Map.entry("auth.session", "auth.session.duration"),
            Map.entry("retrieval", "retrieval.duration"),
            Map.entry("lexical", "lexical.duration"),
            Map.entry("vector", "vector.duration"),
            Map.entry("reranker", "reranker.duration"),
            Map.entry("tool", "tool.duration"),
            Map.entry("llm", "llm.request.duration"),
            Map.entry("agent", "agent.duration"),
            Map.entry("stream", "stream.duration"),
            Map.entry("provider.queue", "ai.provider.queue.stage.duration"));
    private static final AiTelemetry NOOP = new AiTelemetry(null, (Tracer) null);

    private final MeterRegistry meters;
    private final Tracer tracer;
    private final AtomicInteger activeStreams = new AtomicInteger();
    private final ThreadLocal<RequestObservation> current = new ThreadLocal<>();

    @Autowired
    public AiTelemetry(MeterRegistry meters, ObjectProvider<Tracer> tracers) {
        this(meters, tracers.getIfAvailable());
    }

    public AiTelemetry(MeterRegistry meters, Tracer tracer) {
        this.meters = meters;
        this.tracer = tracer;
        if (meters != null) {
            Gauge.builder("active.sse.streams", activeStreams, AtomicInteger::get)
                    .description("Currently open AI SSE responses").register(meters);
            meters.counter("input.tokens", "mode", "other");
            meters.counter("output.tokens", "mode", "other");
            meters.counter("agent.steps");
            meters.counter("rag.no.hit");
        }
    }

    public static AiTelemetry noop() { return NOOP; }

    public RequestObservation startRequest(String mode) {
        String boundedMode = mode != null && MODES.contains(mode) ? mode : "other";
        Span span = tracer == null ? null : tracer.nextSpan().name("ai." + boundedMode)
                .tag("ai.mode", boundedMode).start();
        return new RequestObservation(boundedMode, span, MDC.get("requestId"));
    }

    public RequestObservation currentRequest() { return current.get(); }

    /** Creates a detached span. Use activate() for nested stages on the current thread. */
    public StageTimer stage(String stage) {
        String boundedStage = stage != null && STAGES.containsKey(stage) ? stage : "other";
        Span span = tracer == null ? null : tracer.nextSpan().name(boundedStage).start();
        return new StageTimer(boundedStage, span, current.get());
    }

    public void providerCapacity(java.util.function.IntSupplier active, java.util.function.IntSupplier queued) {
        if (meters == null) return;
        Gauge.builder("ai.provider.in.flight", active, java.util.function.IntSupplier::getAsInt)
                .description("Physical provider slots, retained until underlying work exits").strongReference(true).register(meters);
        Gauge.builder("ai.provider.queued", queued, java.util.function.IntSupplier::getAsInt)
                .description("Requests waiting for physical provider admission").strongReference(true).register(meters);
    }

    public void providerQueueWait(Duration duration, String outcome) {
        recordDuration("ai.provider.queue.wait", outcome, duration.toNanos());
    }

    public void providerRejected() { increment("ai.provider.admission.rejected"); }

    public void noHit() { increment("rag.no.hit"); }
    public void agentStep() { increment("agent.steps"); }

    public void recordTool(String name, boolean success) {
        if (meters == null) return;
        String category = toolCategory(name);
        meters.counter("tool.calls", "tool", category).increment();
        if (!success) meters.counter("tool.failures", "tool", category).increment();
    }

    /** Unknown usage stays unknown; missing/negative token counts never become estimates. */
    public void recordTokens(Integer input, Integer output) {
        if (meters == null) return;
        String mode = current.get() == null ? "other" : current.get().mode;
        if (input != null && input >= 0) meters.counter("input.tokens", "mode", mode).increment(input);
        if (output != null && output >= 0) meters.counter("output.tokens", "mode", mode).increment(output);
    }

    /** Records an asynchronous tool at its actual wall-clock start/end, with the captured parent. */
    public void recordCompletedTool(RequestObservation request, String name, Instant started,
                                    Instant ended, long latencyMillis, boolean success, String errorCode) {
        recordTool(name, success);
        // Discovery is transport setup, not a model-selected agent action. A call
        // blocked by the hard ceiling also did not enter another agent step.
        if (!"mcp_discovery".equals(name) && !"AGENT_STEP_LIMIT".equals(errorCode)) agentStep();
        if (request != null && !"mcp_discovery".equals(name)) request.toolCount.incrementAndGet();
        if (request != null && !"mcp_discovery".equals(name)) request.noteAgentStarted(started);
        recordDuration("tool.duration", success ? "success" : "error",
                TimeUnit.MILLISECONDS.toNanos(Math.max(0, latencyMillis)));
        if (tracer == null) return;
        Span.Builder builder = tracer.spanBuilder().name("tool." + toolCategory(name));
        if (request != null && request.span != null) {
            Span parent = "mcp_discovery".equals(name) ? request.span : request.agentSpan(started);
            builder.setParent(parent.context());
        }
        if (started != null) builder.startTimestamp(started.toEpochMilli(), TimeUnit.MILLISECONDS);
        Span span = builder.start().tag("status", success ? "success" : "error");
        // Error enums are owned by the runtime; do not export exception messages/payloads.
        if (!success && errorCode != null && errorCode.matches("[A-Z0-9_]{1,48}")) span.tag("error.code", errorCode);
        if (!success) markFailed(span, "tool_failed");
        if (ended != null) span.end(ended.toEpochMilli(), TimeUnit.MILLISECONDS);
        else span.end();
    }

    private void increment(String name) { if (meters != null) meters.counter(name).increment(); }

    private static void markFailed(Span span, String status) {
        // Set the real OTel ERROR status for Tempo filtering while exporting no
        // upstream error body, message, stack trace or prompt via exception events.
        span.error(new ObservedFailure(status));
    }

    private static final class ObservedFailure extends RuntimeException {
        private ObservedFailure(String status) { super(status, null, false, false); }
    }

    private void recordDuration(String metric, String status, long nanos, String... additionalTags) {
        if (meters == null) return;
        Timer.Builder builder = Timer.builder(metric).tag("status", status).tags(additionalTags)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofMinutes(5));
        builder.register(meters).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    private static String toolCategory(String value) {
        String name = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (name.contains("web") || name.contains("searchprime")) return "web";
        if (name.contains("documentation") || name.contains("docs")) return "docs";
        if (name.contains("code")) return "code";
        if (name.contains("rag") || name.contains("knowledge")) return "rag";
        if (name.contains("interview")) return "interview";
        if (name.contains("mcp")) return "mcp";
        return "other";
    }

    public final class RequestObservation {
        private final String mode;
        private final Span span;
        private final String requestId;
        private final long startNanos = System.nanoTime();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean streamActive = new AtomicBoolean();
        private final AtomicInteger toolCount = new AtomicInteger();
        private volatile String model = "unknown";
        private volatile String retrievalMode = "unknown";
        private Span agentSpan;
        private Instant agentStarted;

        private RequestObservation(String mode, Span span, String requestId) {
            this.mode = mode;
            this.span = span;
            this.requestId = requestId;
        }

        public Scope activate() { return activateContext(this, span, requestId); }
        public String traceId() { return span == null ? "" : span.context().traceId(); }
        /** Describes configured runtime choices; never pass user input here. */
        public RequestObservation describe(String configuredModel, String configuredRetrievalMode) {
            model = configuredModel != null && configuredModel.matches("[A-Za-z0-9._:/-]{1,96}")
                    ? configuredModel : "unknown";
            retrievalMode = configuredRetrievalMode != null
                    && Set.of("vector", "lexical", "hybrid", "hybrid_rerank").contains(configuredRetrievalMode)
                    ? configuredRetrievalMode : "unknown";
            if (span != null) span.tag("ai.model", model).tag("retrieval.mode", retrievalMode);
            return this;
        }
        public StageTimer stage(String stage) {
            try (Scope ignored = activate()) { return AiTelemetry.this.stage(stage); }
        }

        private synchronized Span agentSpan(Instant started) {
            // A late cancelled callback must not create an unclosed span after request completion.
            if (finished.get()) return span;
            if (agentSpan == null) {
                noteAgentStarted(started);
                agentSpan = tracer.spanBuilder().name("agent").setParent(span.context())
                        .startTimestamp(agentStarted.toEpochMilli(), TimeUnit.MILLISECONDS).start();
            }
            return agentSpan;
        }

        private synchronized void noteAgentStarted(Instant started) {
            if (!finished.get() && agentStarted == null) agentStarted = started == null ? Instant.now() : started;
        }

        private synchronized void finishAgent(String status) {
            if (agentStarted == null) return;
            recordDuration("agent.duration", "success".equals(status) ? "success" : "error",
                    Duration.between(agentStarted, Instant.now()).toNanos());
            if (agentSpan != null) {
                if (!"success".equals(status)) markFailed(agentSpan, status);
                agentSpan.tag("status", status).end();
            }
        }

        public synchronized Scope streamOpened() {
            if (finished.get() || !streamActive.compareAndSet(false, true)) return new Scope(() -> { });
            activeStreams.incrementAndGet();
            return new Scope(this::closeStream);
        }

        private synchronized void closeStream() {
            if (streamActive.compareAndSet(true, false)) activeStreams.decrementAndGet();
        }

        public void finish(String status) {
            if (!finished.compareAndSet(false, true)) return;
            closeStream();
            String boundedStatus = status != null && STATUSES.contains(status) ? status : "error";
            finishAgent(boundedStatus);
            long nanos = System.nanoTime() - startNanos;
            if (meters != null) meters.counter("ai.requests", "mode", mode, "status", boundedStatus).increment();
            recordDuration("ai.request.duration", boundedStatus, nanos, "mode", mode);
            try (Scope ignored = activate()) {
                if (meters != null || span != null) log.atInfo()
                        .addKeyValue("event", "ai.request.completed").addKeyValue("mode", mode)
                        .addKeyValue("status", boundedStatus).addKeyValue("latency_ms", TimeUnit.NANOSECONDS.toMillis(nanos))
                        .addKeyValue("request_id", requestId).addKeyValue("trace_id", traceId())
                        .addKeyValue("model", model).addKeyValue("retrieval_mode", retrievalMode)
                        .addKeyValue("tool_count", toolCount.get()).log("AI request completed");
                if (span != null) {
                    if (!"success".equals(boundedStatus)) markFailed(span, boundedStatus);
                    span.tag("status", boundedStatus); span.end();
                }
            }
        }
    }

    public final class StageTimer implements AutoCloseable {
        private final String stage;
        private final Span span;
        private final RequestObservation request;
        private final long startNanos = System.nanoTime();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean failed;

        private StageTimer(String stage, Span span, RequestObservation request) {
            this.stage = stage;
            this.span = span;
            this.request = request;
        }

        public Scope activate() {
            return activateContext(request, span, request == null ? MDC.get("requestId") : request.requestId);
        }

        public void failure(Throwable error) {
            failed = true;
            if (span != null && error != null) span.tag("error.type", error.getClass().getSimpleName());
        }

        @Override public void close() {
            if (!finished.compareAndSet(false, true)) return;
            String status = failed ? "error" : "success";
            recordDuration(STAGES.getOrDefault(stage, "other.stage.duration"), status, System.nanoTime() - startNanos);
            if (span != null) {
                if (failed) markFailed(span, "stage_failed");
                span.tag("status", status); span.end();
            }
        }
    }

    private Scope activateContext(RequestObservation request, Span span, String requestId) {
        RequestObservation previous = current.get();
        String previousRequestId = MDC.get("requestId");
        if (request == null) current.remove(); else current.set(request);
        if (requestId == null) MDC.remove("requestId"); else MDC.put("requestId", requestId);
        Tracer.SpanInScope traceScope = tracer == null || span == null ? null : tracer.withSpan(span);
        return new Scope(() -> {
            if (traceScope != null) traceScope.close();
            if (previous == null) current.remove(); else current.set(previous);
            if (previousRequestId == null) MDC.remove("requestId"); else MDC.put("requestId", previousRequestId);
        });
    }

    public static final class Scope implements AutoCloseable {
        private final Runnable close;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Scope(Runnable close) { this.close = close; }
        @Override public void close() { if (closed.compareAndSet(false, true)) close.run(); }
    }
}
