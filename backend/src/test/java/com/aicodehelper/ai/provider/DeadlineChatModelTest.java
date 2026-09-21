package com.aicodehelper.ai.provider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Timeout(10)
class DeadlineChatModelTest {
    private static final ChatRequest REQUEST = ChatRequest.builder().messages(UserMessage.from("hello")).build();
    private static final ChatResponse RESPONSE = ChatResponse.builder().aiMessage(AiMessage.from("answer")).build();

    @Test
    void keepsDefaultsCapabilitiesAndListenerOptionsWithoutDoubleNotifications() {
        RecordingListener listener = new RecordingListener();
        ChatRequestParameters defaults = ChatRequestParameters.builder()
                .modelName("configured-model").temperature(0.2).maxOutputTokens(80).build();
        AtomicReference<ChatRequest> received = new AtomicReference<>();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) { received.set(request); return RESPONSE; }
            @Override public ChatRequestParameters defaultRequestParameters() { return defaults; }
            @Override public List<ChatModelListener> listeners() { return List.of(listener); }
            @Override public ModelProvider provider() { return ModelProvider.OPEN_AI; }
            @Override public Set<Capability> supportedCapabilities() { return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA); }
        };

        try (ProviderCallExecutor executor = executor()) {
            DeadlineChatModel model = new DeadlineChatModel(delegate, executor, Duration.ofSeconds(2));
            ChatRequest request = ChatRequest.builder().messages(REQUEST.messages())
                    .parameters(ChatRequestParameters.builder().temperature(0.7).build()).build();
            ChatRequestOptions options = ChatRequestOptions.builder().addListenerAttribute("request-id", "abc").build();

            assertThat(model.chat(request, options)).isSameAs(RESPONSE);
            assertThat(received.get().parameters().modelName()).isEqualTo("configured-model");
            assertThat(received.get().parameters().temperature()).isEqualTo(0.7);
            assertThat(received.get().parameters().maxOutputTokens()).isEqualTo(80);
            assertThat(model.defaultRequestParameters()).isSameAs(defaults);
            assertThat(model.provider()).isEqualTo(ModelProvider.OPEN_AI);
            assertThat(model.supportedCapabilities()).containsExactly(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
            assertThat(model.listeners()).containsExactly(listener);
            assertThat(listener.requests.get()).isEqualTo(1);
            assertThat(listener.responses.get()).isEqualTo(1);
            assertThat(listener.errors.get()).isZero();
            assertThat(listener.request.get().attributes()).containsEntry("request-id", "abc");
            assertThat(listener.response.get().attributes()).containsEntry("request-id", "abc");
            assertThat(listener.request.get().modelProvider()).isEqualTo(ModelProvider.OPEN_AI);

            assertThat(model.chat(REQUEST)).isSameAs(RESPONSE);
            assertThat(listener.requests.get()).isEqualTo(2);
            assertThat(listener.responses.get()).isEqualTo(2);
        }
    }

    @Test
    void timedOutProviderRetainsItsSlotUntilActualExitAndLateResultCannotEscape() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    waitIgnoringInterrupt(release);
                    finished.countDown();
                }
                return RESPONSE;
            }
        };

        try (ProviderCallExecutor executor = executor()) {
            DeadlineChatModel model = new DeadlineChatModel(delegate, executor, Duration.ofMillis(150));
            try {
                assertThatThrownBy(() -> model.chat(REQUEST)).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_TIMEOUT"));
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.inFlightCount()).isEqualTo(1);
                assertThat(finished.getCount()).isEqualTo(1);
                assertThatThrownBy(() -> model.chat(REQUEST)).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CAPACITY"));
                assertThat(calls.get()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
            assertThat(model.chat(REQUEST)).isSameAs(RESPONSE);
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void providerFailureReleasesCapacityAndNotifiesErrorListenerOnce() {
        RecordingListener listener = new RecordingListener();
        IllegalStateException failure = new IllegalStateException("test failure");
        AtomicInteger attempts = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                if (attempts.incrementAndGet() == 1) throw failure;
                return RESPONSE;
            }
            @Override public List<ChatModelListener> listeners() { return List.of(listener); }
        };
        try (ProviderCallExecutor executor = executor()) {
            DeadlineChatModel model = new DeadlineChatModel(delegate, executor, Duration.ofSeconds(2));
            assertThatThrownBy(() -> model.chat(REQUEST)).isSameAs(failure);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
            assertThat(listener.requests.get()).isEqualTo(1);
            assertThat(listener.responses.get()).isZero();
            assertThat(listener.errors.get()).isEqualTo(1);
            assertThat(model.chat(REQUEST)).isSameAs(RESPONSE);
        }
    }

    @Test
    void callerInterruptionReportsCancellationAndKeepsTheInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                entered.countDown();
                waitIgnoringInterrupt(release);
                return RESPONSE;
            }
        };
        try (ProviderCallExecutor executor = executor()) {
            DeadlineChatModel model = new DeadlineChatModel(delegate, executor, Duration.ofSeconds(5));
            Thread caller = Thread.ofVirtual().start(() -> {
                try { model.chat(REQUEST); }
                catch (Throwable error) { reported.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                caller.interrupt();
                caller.join(2_000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(reported.get()).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CANCELLED"));
                assertThat(interrupted.get()).isTrue();
                assertThat(executor.inFlightCount()).isEqualTo(1);
            } finally { release.countDown(); caller.interrupt(); }
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
        }
    }

    @Test
    void unrecoverableProviderErrorAlsoReleasesCapacity() {
        AssertionError failure = new AssertionError("provider error");
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) { throw failure; }
        };
        try (ProviderCallExecutor executor = executor()) {
            DeadlineChatModel model = new DeadlineChatModel(delegate, executor, Duration.ofSeconds(2));
            assertThatThrownBy(() -> model.chat(REQUEST)).isSameAs(failure);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
            assertThat(executor.call(Duration.ofSeconds(2), () -> "recovered")).isEqualTo("recovered");
        }
    }

    private static ProviderCallExecutor executor() {
        ProviderProperties properties = new ProviderProperties();
        properties.setMaxInFlight(1);
        return new ProviderCallExecutor(properties);
    }

    static void waitIgnoringInterrupt(CountDownLatch latch) {
        boolean done = false;
        while (!done) {
            try { latch.await(); done = true; }
            catch (InterruptedException ignored) { }
        }
    }

    private static final class RecordingListener implements ChatModelListener {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger responses = new AtomicInteger();
        private final AtomicInteger errors = new AtomicInteger();
        private final AtomicReference<ChatModelRequestContext> request = new AtomicReference<>();
        private final AtomicReference<ChatModelResponseContext> response = new AtomicReference<>();
        @Override public void onRequest(ChatModelRequestContext context) { requests.incrementAndGet(); request.set(context); }
        @Override public void onResponse(ChatModelResponseContext context) { responses.incrementAndGet(); response.set(context); }
        @Override public void onError(ChatModelErrorContext context) { errors.incrementAndGet(); }
    }
}
