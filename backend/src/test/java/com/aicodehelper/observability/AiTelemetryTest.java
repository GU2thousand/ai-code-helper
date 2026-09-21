package com.aicodehelper.observability;

import com.aicodehelper.agent.ToolError;
import com.aicodehelper.agent.ToolMetadata;
import com.aicodehelper.agent.ToolRequest;
import com.aicodehelper.agent.ToolResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiTelemetryTest {
    @AfterEach void clearMdc() { MDC.clear(); }

    @Test void sseCompletionRacesRecordExactlyOnceAndReleaseGauge() throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            AiTelemetry telemetry = new AiTelemetry(meters, (io.micrometer.tracing.Tracer) null);
            var request = telemetry.startRequest("sse");
            var stream = request.streamOpened();
            assertThat(meters.get("active.sse.streams").gauge().value()).isEqualTo(1);
            var tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> workers.submit(() -> { request.finish(i % 2 == 0 ? "success" : "cancelled"); stream.close(); }))
                    .toList();
            for (var task : tasks) task.get();
            assertThat(meters.get("active.sse.streams").gauge().value()).isZero();
            assertThat(meters.find("ai.requests").counters().stream().mapToDouble(c -> c.count()).sum()).isEqualTo(1);
            request.streamOpened().close();
            assertThat(meters.get("active.sse.streams").gauge().value()).isZero();
        }
    }

    @Test void asyncChildrenStayOnRequestTraceAndRestoreWorkerContext() throws Exception {
        List<SpanData> spans = new CopyOnWriteArrayList<>();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(collector(spans)).build();
             var worker = Executors.newSingleThreadExecutor()) {
            var meters = new SimpleMeterRegistry();
            var tracer = new OtelTracer(provider.get("telemetry-test"), new OtelCurrentTraceContext(), event -> { });
            AiTelemetry telemetry = new AiTelemetry(meters, tracer);
            MDC.put("requestId", "request-123");
            var request = telemetry.startRequest("rag");
            assertThat(telemetry.currentRequest()).isNull();
            var result = worker.submit(() -> {
                MDC.put("requestId", "worker-before");
                try (var scope = request.activate(); var retrieval = telemetry.stage("retrieval");
                     var nested = retrieval.activate(); var vector = telemetry.stage("vector")) {
                    assertThat(telemetry.currentRequest()).isSameAs(request);
                    assertThat(MDC.get("requestId")).isEqualTo("request-123");
                    vector.failure(new IllegalStateException("private prompt must not be exported"));
                }
                assertThat(telemetry.currentRequest()).isNull();
                assertThat(MDC.get("requestId")).isEqualTo("worker-before");
                assertThat(tracer.currentSpan()).isNull();
                request.finish("success");
                assertThat(MDC.get("requestId")).isEqualTo("worker-before");
                MDC.clear();
            });
            result.get();
            assertThat(spans).hasSize(3);
            SpanData root = spans.stream().filter(s -> s.getName().equals("ai.rag")).findFirst().orElseThrow();
            SpanData retrieval = spans.stream().filter(s -> s.getName().equals("retrieval")).findFirst().orElseThrow();
            SpanData vector = spans.stream().filter(s -> s.getName().equals("vector")).findFirst().orElseThrow();
            assertThat(retrieval.getParentSpanId()).isEqualTo(root.getSpanId());
            assertThat(vector.getParentSpanId()).isEqualTo(retrieval.getSpanId());
            assertThat(vector.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
            assertThat(vector.getEvents().toString()).doesNotContain("private prompt");
            assertThat(spans).allSatisfy(s -> {
                assertThat(s.getTraceId()).isEqualTo(request.traceId());
                assertThat(s.getAttributes().toString()).doesNotContain("private prompt", "request-123");
            });
        }
    }

    @Test void completionOnlyToolObserverPreservesParentAndRealTimestampsWithoutArguments() throws Exception {
        List<SpanData> spans = new CopyOnWriteArrayList<>();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(collector(spans)).build();
             var worker = Executors.newSingleThreadExecutor()) {
            var meters = new SimpleMeterRegistry();
            var tracer = new OtelTracer(provider.get("tool-test"), new OtelCurrentTraceContext(), event -> { });
            AiTelemetry telemetry = new AiTelemetry(meters, tracer);
            var request = telemetry.startRequest("chat");
            var observer = new TelemetryToolRuntimeObserver(telemetry);
            java.util.function.Consumer<ToolResult> callback;
            try (var ignored = request.activate()) { callback = observer.forRequest("secret-conversation"); }
            Instant start = Instant.parse("2026-09-20T12:00:00Z");
            var result = new ToolResult(new ToolRequest("webSearchPrime", "secret argument"), false, null,
                    ToolError.of(ToolError.Code.TOOL_TIMEOUT), new ToolMetadata(start, start.plusMillis(75), 75, 1, 0));
            worker.submit(() -> callback.accept(result)).get();
            request.finish("error");
            SpanData tool = spans.stream().filter(s -> s.getName().equals("tool.web")).findFirst().orElseThrow();
            SpanData agent = spans.stream().filter(s -> s.getName().equals("agent")).findFirst().orElseThrow();
            assertThat(tool.getTraceId()).isEqualTo(request.traceId());
            assertThat(tool.getParentSpanId()).isEqualTo(agent.getSpanId());
            assertThat(tool.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
            assertThat(tool.getEndEpochNanos() - tool.getStartEpochNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(75));
            assertThat(tool.getAttributes().toString()).doesNotContain("secret");
            assertThat(meters.get("tool.failures").tag("tool", "web").counter().count()).isEqualTo(1);
            assertThat(meters.get("agent.steps").counter().count()).isEqualTo(1);
        }
    }

    @Test void prometheusExportsHistogramAndBoundedLabelsWithoutInventingUsage() {
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            AiTelemetry telemetry = new AiTelemetry(meters, (io.micrometer.tracing.Tracer) null);
            telemetry.startRequest("raw prompt as mode").finish("api key as status");
            telemetry.recordTool("secret-private-user-tool-1234", false);
            telemetry.recordTokens(null, -1);
            telemetry.recordTokens(12, 8);
            String scrape = meters.scrape();
            assertThat(scrape).contains("ai_request_duration_seconds_bucket", "ai_requests_total", "status=\"error\"", "mode=\"other\"");
            assertThat(scrape).contains("input_tokens_total{mode=\"other\"} 12.0", "output_tokens_total{mode=\"other\"} 8.0", "tool=\"other\"");
            assertThat(scrape).doesNotContain("raw prompt", "api key", "secret-private");
        } finally { meters.close(); }
    }

    private static SpanProcessor collector(List<SpanData> output) {
        return new SpanProcessor() {
            @Override public void onStart(Context context, ReadWriteSpan span) { }
            @Override public boolean isStartRequired() { return false; }
            @Override public void onEnd(ReadableSpan span) { output.add(span.toSpanData()); }
            @Override public boolean isEndRequired() { return true; }
        };
    }
}
