package com.aicodehelper.ai.provider;

import com.aicodehelper.observability.AiTelemetry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(10)
class DeadlineStreamingChatModelTest {
    private static final ChatRequest REQUEST = ChatRequest.builder().messages(UserMessage.from("hello")).build();
    private static final ChatResponse RESPONSE = ChatResponse.builder().aiMessage(AiMessage.from("answer")).build();

    @Test
    void timeoutSuppressesEveryLateCallbackAndHoldsCapacityUntilProviderFinishes() throws Exception {
        DeferredProvider provider = new DeferredProvider();
        RecordingHandler downstream = new RecordingHandler();
        try (ProviderCallExecutor executor = executor()) {
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(provider, executor,
                    Duration.ofMillis(150), Duration.ofSeconds(2));
            model.chat(REQUEST, downstream);
            assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(downstream.terminal.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(downstream.failure.get()).isInstanceOfSatisfying(ProviderCallException.class,
                    error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_TIMEOUT"));
            assertThat(executor.inFlightCount()).isEqualTo(1);

            RecordingHandler rejected = new RecordingHandler();
            model.chat(REQUEST, rejected);
            assertThat(rejected.failure.get()).isInstanceOfSatisfying(ProviderCallException.class,
                    error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CAPACITY"));

            StreamingChatResponseHandler guarded = provider.handler.get();
            guarded.onPartialResponse("late text");
            guarded.onPartialThinking(new PartialThinking("late thought"));
            guarded.onPartialToolCall(partialTool());
            guarded.onCompleteToolCall(completeTool());
            guarded.onUnmappedRawEvent("late raw event");
            guarded.onCompleteResponse(RESPONSE);
            guarded.onError(new IllegalStateException("duplicate terminal"));
            assertThat(downstream.events).containsExactly("error");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
        }
    }

    @Test
    void cancellationHandleStopsProviderAndSuppressesTerminalMemoryCallback() throws Exception {
        DeferredProvider provider = new DeferredProvider();
        RecordingHandler downstream = new RecordingHandler();
        AtomicBoolean providerCancelled = new AtomicBoolean();
        StreamingHandle providerHandle = new StreamingHandle() {
            @Override public void cancel() { providerCancelled.set(true); }
            @Override public boolean isCancelled() { return providerCancelled.get(); }
        };
        try (ProviderCallExecutor executor = executor()) {
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(provider, executor,
                    Duration.ofSeconds(2), Duration.ofSeconds(3));
            model.chat(REQUEST, downstream);
            assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
            StreamingChatResponseHandler guarded = provider.handler.get();
            guarded.onPartialResponse(new PartialResponse("first"), new PartialResponseContext(providerHandle));
            assertThat(downstream.handle.get()).isNotNull().isNotSameAs(providerHandle);
            downstream.handle.get().cancel();
            assertThat(downstream.handle.get().isCancelled()).isTrue();
            await().atMost(Duration.ofSeconds(2)).untilTrue(providerCancelled);
            assertThat(executor.inFlightCount()).isEqualTo(1);

            guarded.onPartialResponse("late");
            guarded.onCompleteResponse(RESPONSE);
            guarded.onError(new IllegalStateException("late"));
            assertThat(downstream.events).containsExactly("text:first");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
        }
    }

    @Test
    void requestCancellationBeforeFirstTokenSuppressesLateProviderWork() throws Exception {
        AiTelemetry telemetry = new AiTelemetry(null, (io.micrometer.tracing.Tracer) null);
        var request = telemetry.startRequest("sse");
        DeferredProvider provider = new DeferredProvider();
        RecordingHandler downstream = new RecordingHandler();
        ProviderProperties properties = new ProviderProperties();
        properties.setMaxInFlight(1);
        properties.setMaxQueued(0);
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties, telemetry)) {
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(provider, executor,
                    Duration.ofSeconds(2), Duration.ofSeconds(3));
            try (var ignored = request.activate()) { model.chat(REQUEST, downstream); }
            assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
            executor.cancelRequest(request);
            assertThat(executor.inFlightCount()).isEqualTo(1);
            StreamingChatResponseHandler guarded = provider.handler.get();
            guarded.onPartialResponse("late first token");
            guarded.onPartialThinking(new PartialThinking("late thinking"));
            guarded.onCompleteResponse(RESPONSE);
            assertThat(downstream.events).isEmpty();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
        } finally { request.finish("cancelled"); }
    }

    @Test
    void previouslyCancelledRequestCannotStartANewProviderRound() throws Exception {
        AiTelemetry telemetry = new AiTelemetry(null, (io.micrometer.tracing.Tracer) null);
        var cancelled = telemetry.startRequest("sse");
        var independent = telemetry.startRequest("sse");
        DeferredProvider provider = new DeferredProvider();
        RecordingHandler cancelledHandler = new RecordingHandler();
        ProviderProperties properties = new ProviderProperties();
        properties.setMaxInFlight(1);
        properties.setMaxQueued(0);
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties, telemetry)) {
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(provider, executor,
                    Duration.ofSeconds(2), Duration.ofSeconds(3));
            executor.cancelRequest(cancelled);
            try (var ignored = cancelled.activate()) { model.chat(REQUEST, cancelledHandler); }
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
            assertThat(provider.handler.get()).isNull();
            assertThat(cancelledHandler.events).isEmpty();

            RecordingHandler independentHandler = new RecordingHandler();
            try (var ignored = independent.activate()) { model.chat(REQUEST, independentHandler); }
            assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
            provider.handler.get().onCompleteResponse(RESPONSE);
            assertThat(independentHandler.events).containsExactly("complete");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
        } finally { cancelled.finish("cancelled"); independent.finish("success"); }
    }

    @Test
    void requestCancellationWaitsForInProgressCompletionBeforeMemoryRollbackCanRun() throws Exception {
        AiTelemetry telemetry = new AiTelemetry(null, (io.micrometer.tracing.Tracer) null);
        var request = telemetry.startRequest("sse");
        DeferredProvider provider = new DeferredProvider();
        CountDownLatch delivering = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        List<String> memoryEvents = new CopyOnWriteArrayList<>();
        StreamingChatResponseHandler downstream = new StreamingChatResponseHandler() {
            @Override public void onCompleteResponse(ChatResponse response) {
                delivering.countDown();
                DeadlineChatModelTest.waitIgnoringInterrupt(releaseDelivery);
                memoryEvents.add("provider-memory-write");
            }
            @Override public void onError(Throwable error) { }
        };
        try (ProviderCallExecutor executor = new ProviderCallExecutor(new ProviderProperties(), telemetry)) {
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(provider, executor,
                    Duration.ofSeconds(2), Duration.ofSeconds(3));
            try (var ignored = request.activate()) { model.chat(REQUEST, downstream); }
            assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
            Thread completion = Thread.ofVirtual().start(() -> provider.handler.get().onCompleteResponse(RESPONSE));
            assertThat(delivering.await(2, TimeUnit.SECONDS)).isTrue();
            Thread cancellation = Thread.ofVirtual().start(() -> {
                cancellationStarted.countDown();
                executor.cancelRequest(request);
                memoryEvents.add("controller-rollback");
                cancellationFinished.countDown();
            });
            try {
                assertThat(cancellationStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(cancellationFinished.await(150, TimeUnit.MILLISECONDS)).isFalse();
            } finally { releaseDelivery.countDown(); }
            completion.join(2_000);
            cancellation.join(2_000);
            assertThat(completion.isAlive()).isFalse();
            assertThat(cancellation.isAlive()).isFalse();
            assertThat(memoryEvents).containsExactly("provider-memory-write", "controller-rollback");
        } finally { releaseDelivery.countDown(); request.finish("cancelled"); }
    }

    @Test
    void forwardsThinkingToolAndRawCallbacksAndLegacyTextHasCancellationHandle() throws Exception {
        DeferredProvider provider = new DeferredProvider();
        RecordingHandler downstream = new RecordingHandler();
        try (ProviderCallExecutor executor = executor()) {
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(provider, executor,
                    Duration.ofSeconds(2), Duration.ofSeconds(3));
            model.chat(REQUEST, downstream);
            assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
            StreamingChatResponseHandler guarded = provider.handler.get();
            guarded.onPartialThinking(new PartialThinking("thinking"));
            guarded.onPartialToolCall(partialTool());
            guarded.onCompleteToolCall(completeTool());
            guarded.onUnmappedRawEvent("raw");
            guarded.onPartialResponse("legacy");
            guarded.onCompleteResponse(RESPONSE);
            assertThat(downstream.events).containsExactly("thinking:thinking", "partial-tool", "complete-tool", "raw", "text:legacy", "complete");
            assertThat(downstream.handle.get()).isNotNull();
            assertThat(downstream.handle.get().isCancelled()).isFalse();
            assertThat(downstream.response.get()).isSameAs(RESPONSE);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
        }
    }

    @Test
    void activityCancelsFirstTokenDeadlineButStillEnforcesTotalDeadline() throws Exception {
        DeferredProvider provider = new DeferredProvider();
        RecordingHandler downstream = new RecordingHandler();
        try (ProviderCallExecutor executor = executor()) {
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(provider, executor,
                    Duration.ofMillis(300), Duration.ofMillis(650));
            model.chat(REQUEST, downstream);
            assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
            provider.handler.get().onPartialResponse("first");
            assertThat(downstream.terminal.await(400, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(downstream.terminal.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(downstream.failure.get()).isInstanceOfSatisfying(ProviderCallException.class,
                    error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_TIMEOUT"));
            assertThat(downstream.events).containsExactly("text:first", "error");
            provider.handler.get().onCompleteResponse(RESPONSE);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
        }
    }

    @Test
    void blockingProviderCancellationCannotBlockTimeoutNotification() throws Exception {
        DeferredProvider provider = new DeferredProvider();
        RecordingHandler downstream = new RecordingHandler();
        CountDownLatch cancelling = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        StreamingHandle providerHandle = new StreamingHandle() {
            @Override public void cancel() {
                cancelling.countDown();
                DeadlineChatModelTest.waitIgnoringInterrupt(releaseCancel);
            }
            @Override public boolean isCancelled() { return false; }
        };
        try (ProviderCallExecutor executor = executor()) {
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(provider, executor,
                    Duration.ofSeconds(2), Duration.ofMillis(250));
            model.chat(REQUEST, downstream);
            assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
            provider.handler.get().onPartialResponse(new PartialResponse("first"), new PartialResponseContext(providerHandle));
            try {
                assertThat(cancelling.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(downstream.terminal.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(downstream.failure.get()).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_TIMEOUT"));
                assertThat(executor.inFlightCount()).isEqualTo(1);
            } finally { releaseCancel.countDown(); }
            provider.handler.get().onCompleteResponse(RESPONSE);
            assertThat(downstream.events).containsExactly("text:first", "error");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
        }
    }

    @Test
    void downstreamCompletionFailureReachesErrorHandlerOnceAndReleasesCapacity() throws Exception {
        IllegalStateException completionFailure = new IllegalStateException("completion delivery failed");
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch errorReceived = new CountDownLatch(1);
        StreamingChatModel provider = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(RESPONSE);
                handler.onError(new IllegalStateException("duplicate provider terminal"));
            }
        };
        StreamingChatResponseHandler downstream = new StreamingChatResponseHandler() {
            @Override public void onCompleteResponse(ChatResponse response) {
                events.add("complete");
                throw completionFailure;
            }
            @Override public void onError(Throwable error) {
                events.add("error");
                failure.set(error);
                errorReceived.countDown();
            }
        };
        try (ProviderCallExecutor executor = executor()) {
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(provider, executor,
                    Duration.ofSeconds(2), Duration.ofSeconds(3));
            model.chat(REQUEST, downstream);
            assertThat(errorReceived.await(2, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
            assertThat(events).containsExactly("complete", "error");
            assertThat(failure.get()).isSameAs(completionFailure);
        }
    }

    private static ProviderCallExecutor executor() {
        ProviderProperties properties = new ProviderProperties();
        properties.setMaxInFlight(1);
        properties.setMaxQueued(0);
        return new ProviderCallExecutor(properties);
    }

    private static PartialToolCall partialTool() {
        return PartialToolCall.builder().index(0).id("tool-1").name("lookup").partialArguments("{").build();
    }

    private static CompleteToolCall completeTool() {
        return new CompleteToolCall(0, ToolExecutionRequest.builder().id("tool-1").name("lookup").arguments("{}").build());
    }

    private static final class DeferredProvider implements StreamingChatModel {
        private final AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
        private final CountDownLatch started = new CountDownLatch(1);
        @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            this.handler.set(handler);
            started.countDown();
        }
    }

    private static final class RecordingHandler implements StreamingChatResponseHandler {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicReference<StreamingHandle> handle = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<ChatResponse> response = new AtomicReference<>();
        private final CountDownLatch terminal = new CountDownLatch(1);
        @Override public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
            events.add("text:" + partial.text());
            handle.set(context.streamingHandle());
        }
        @Override public void onPartialThinking(PartialThinking thinking) { events.add("thinking:" + thinking.text()); }
        @Override public void onPartialToolCall(PartialToolCall toolCall) { events.add("partial-tool"); }
        @Override public void onCompleteToolCall(CompleteToolCall toolCall) { events.add("complete-tool"); }
        @Override public void onUnmappedRawEvent(Object event) { events.add("raw"); }
        @Override public void onCompleteResponse(ChatResponse response) { events.add("complete"); this.response.set(response); terminal.countDown(); }
        @Override public void onError(Throwable error) { events.add("error"); failure.set(error); terminal.countDown(); }
    }
}
