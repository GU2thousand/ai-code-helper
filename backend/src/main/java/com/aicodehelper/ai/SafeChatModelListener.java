package com.aicodehelper.ai;

import com.aicodehelper.observability.AiTelemetry;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class SafeChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(SafeChatModelListener.class);
    private static final String START_NANOS = SafeChatModelListener.class.getName() + ".startNanos";
    private static final String STAGE = SafeChatModelListener.class.getName() + ".stage";
    private final AiTelemetry telemetry;

    public SafeChatModelListener() { this(AiTelemetry.noop()); }

    public SafeChatModelListener(AiTelemetry telemetry) { this.telemetry = telemetry; }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START_NANOS, System.nanoTime());
        context.attributes().put(STAGE, telemetry.stage("llm"));
        log.atInfo().addKeyValue("event", "llm.request.started")
                .addKeyValue("provider", context.modelProvider()).addKeyValue("model", context.chatRequest().modelName())
                .addKeyValue("message_count", context.chatRequest().messages().size()).log("LLM request started");
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        Object stage = context.attributes().remove(STAGE);
        withContext(stage, () -> {
            if (context.chatResponse().tokenUsage() != null) {
                telemetry.recordTokens(context.chatResponse().tokenUsage().inputTokenCount(),
                        context.chatResponse().tokenUsage().outputTokenCount());
            }
            log.atInfo().addKeyValue("event", "llm.request.completed")
                    .addKeyValue("provider", context.modelProvider()).addKeyValue("model", context.chatResponse().modelName())
                    .addKeyValue("latency_ms", durationMillis(context.attributes().get(START_NANOS)))
                    .addKeyValue("finish_reason", context.chatResponse().finishReason())
                    .addKeyValue("total_tokens", context.chatResponse().tokenUsage() == null
                            ? null : context.chatResponse().tokenUsage().totalTokenCount()).log("LLM request completed");
        });
        if (stage instanceof AiTelemetry.StageTimer timer) timer.close();
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        Object stage = context.attributes().remove(STAGE);
        if (stage instanceof AiTelemetry.StageTimer timer) {
            timer.failure(context.error());
        }
        withContext(stage, () -> log.atWarn().addKeyValue("event", "llm.request.failed")
                    .addKeyValue("provider", context.modelProvider())
                    .addKeyValue("latency_ms", durationMillis(context.attributes().get(START_NANOS)))
                    .addKeyValue("error_type", context.error().getClass().getSimpleName()).log("LLM request failed"));
        if (stage instanceof AiTelemetry.StageTimer timer) timer.close();
    }

    private void withContext(Object stage, Runnable action) {
        if (stage instanceof AiTelemetry.StageTimer timer) {
            try (var ignored = timer.activate()) { action.run(); }
        } else action.run();
    }

    private long durationMillis(Object startNanos) {
        if (startNanos instanceof Long start) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
        return -1;
    }
}
