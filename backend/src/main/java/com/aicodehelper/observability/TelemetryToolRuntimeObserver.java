package com.aicodehelper.observability;

import com.aicodehelper.agent.ToolResult;
import com.aicodehelper.agent.ToolRuntimeObserver;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Captures request context before tool execution leaves the servlet thread. */
@Component
public final class TelemetryToolRuntimeObserver implements ToolRuntimeObserver {
    private final AiTelemetry telemetry;

    public TelemetryToolRuntimeObserver(AiTelemetry telemetry) { this.telemetry = telemetry; }

    @Override public Consumer<ToolResult> forRequest(String conversationKey) {
        AiTelemetry.RequestObservation request = telemetry.currentRequest();
        return result -> record(request, result);
    }

    @Override public void completed(String conversationKey, ToolResult result) {
        record(telemetry.currentRequest(), result);
    }

    private void record(AiTelemetry.RequestObservation request, ToolResult result) {
        if (request == null) { recordResult(null, result); return; }
        try (var scope = request.activate()) { recordResult(request, result); }
    }

    private void recordResult(AiTelemetry.RequestObservation request, ToolResult result) {
        telemetry.recordCompletedTool(request, result.request() == null ? null : result.request().toolName(),
                result.metadata().startedAt(), result.metadata().endedAt(), result.metadata().latencyMs(),
                result.success(), result.error() == null ? null : result.error().code().name());
    }
}
