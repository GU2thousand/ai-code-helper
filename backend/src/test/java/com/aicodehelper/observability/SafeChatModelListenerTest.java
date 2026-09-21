package com.aicodehelper.observability;

import com.aicodehelper.ai.SafeChatModelListener;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class SafeChatModelListenerTest {
    @Test void providerCallbacksOnAnotherThreadRecordMeasuredUsageAndFailure() throws Exception {
        var meters = new SimpleMeterRegistry();
        var telemetry = new AiTelemetry(meters, (io.micrometer.tracing.Tracer) null);
        var listener = new SafeChatModelListener(telemetry);
        var request = ChatRequest.builder().messages(UserMessage.from("private input")).build();
        var response = ChatResponse.builder().aiMessage(AiMessage.from("private answer"))
                .tokenUsage(new TokenUsage(14, 9)).build();
        var successAttributes = new HashMap<Object, Object>();
        var failureAttributes = new HashMap<Object, Object>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OTHER, successAttributes));
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OTHER, failureAttributes));
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> listener.onResponse(new ChatModelResponseContext(response, request,
                    ModelProvider.OTHER, successAttributes))).get();
            worker.submit(() -> listener.onError(new ChatModelErrorContext(new IllegalStateException("private detail"),
                    request, ModelProvider.OTHER, failureAttributes))).get();
            assertThat(worker.submit(telemetry::currentRequest).get()).isNull();
        }
        assertThat(meters.get("llm.request.duration").tag("status", "success").timer().count()).isEqualTo(1);
        assertThat(meters.get("llm.request.duration").tag("status", "error").timer().count()).isEqualTo(1);
        assertThat(meters.get("input.tokens").counter().count()).isEqualTo(14);
        assertThat(meters.get("output.tokens").counter().count()).isEqualTo(9);
        meters.close();
    }
}
