package com.aicodehelper;

import com.aicodehelper.agent.AgentProperties;
import com.aicodehelper.agent.BoundedToolRuntime;
import com.aicodehelper.agent.ToolError;
import com.aicodehelper.agent.ToolRequest;
import com.aicodehelper.agent.ToolResult;
import com.aicodehelper.ai.AiChatService;
import com.aicodehelper.ai.AiExecutionRegistry;
import com.aicodehelper.ai.ChatStreamTicketService;
import com.aicodehelper.ai.ModelRuntimeInfo;
import com.aicodehelper.ai.api.AiChatController;
import com.aicodehelper.ai.provider.DeadlineStreamingChatModel;
import com.aicodehelper.ai.provider.ProviderCallExecutor;
import com.aicodehelper.ai.provider.ProviderProperties;
import com.aicodehelper.config.AppProperties;
import com.aicodehelper.memory.ConversationMemoryManager;
import com.aicodehelper.memory.ConversationMemoryRegistry;
import com.aicodehelper.observability.AiTelemetry;
import com.aicodehelper.retrieval.RetrievalProperties;
import com.aicodehelper.user.ClientIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.TokenStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises controller cleanup against Spring's real emitter lock and the provider callback fence. */
@Timeout(10)
class SseCancellationConcurrencyTest {
    private static final String KEY = "guest:test:memory:concurrency";
    private static final ChatResponse RESPONSE = ChatResponse.builder().aiMessage(AiMessage.from("late answer")).build();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializationErrorDoesNotDeadlockWithAnInProgressSseWrite(boolean cleanupExecutorStopped) throws Exception {
        try (Fixture fixture = new Fixture()) {
            SseEmitter emitter = fixture.start();
            if (cleanupExecutorStopped) fixture.cleanup.shutdown();
            Lock emitterLock = (Lock) ReflectionTestUtils.getField(emitter, "writeLock");
            CountDownLatch lockHeld = new CountDownLatch(1);
            CountDownLatch beginError = new CountDownLatch(1);
            CountDownLatch callbackReturned = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // initializeWithError really invokes onError while holding this same reentrant lock.
            // Hold it first so the provider must enter Guard -> controller -> SseEmitter.send.
            Thread framework = Thread.ofVirtual().start(() -> {
                emitterLock.lock();
                try {
                    lockHeld.countDown();
                    beginError.await();
                    ReflectionTestUtils.invokeMethod(emitter, "initializeWithError", new IOException("async init failed"));
                    callbackReturned.countDown();
                } catch (Throwable error) { failure.set(error); }
                finally { emitterLock.unlock(); }
            });
            assertThat(lockHeld.await(2, TimeUnit.SECONDS)).isTrue();
            Thread delivery = Thread.ofVirtual().start(() -> {
                try { fixture.providerHandler.get().onPartialResponse("racing token"); }
                catch (IllegalStateException alreadyCompleted) {
                    // Spring can reject this send after initializeWithError marks the emitter complete.
                }
            });
            try {
                await().atMost(Duration.ofSeconds(2)).until(() -> Arrays.stream(delivery.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(SseEmitter.class.getName())
                                && frame.getMethodName().equals("send")));
                beginError.countDown();
                assertThat(callbackReturned.await(2, TimeUnit.SECONDS))
                        .as("MVC error callback must release the emitter lock without waiting for the provider fence")
                        .isTrue();
                framework.join(2_000);
                delivery.join(2_000);
                assertThat(framework.isAlive()).isFalse();
                assertThat(delivery.isAlive()).isFalse();
                assertThat(failure.get()).isNull();
                fixture.assertRolledBackAndClosed();

                // The deliberately asynchronous provider has not acknowledged termination yet.
                assertThat(fixture.providers.inFlightCount()).isEqualTo(1);
                fixture.providerHandler.get().onCompleteResponse(RESPONSE);
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                        assertThat(fixture.providers.inFlightCount()).isZero());
                fixture.assertOriginalMemory();
            } finally { beginError.countDown(); }
        }
    }

    @Test
    void disconnectCancelsRunningToolBeforeWaitingForProviderDeliveryAndRollsBackAfterward() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch toolStarted = new CountDownLatch(1);
            CountDownLatch toolInterrupted = new CountDownLatch(1);
            CountDownLatch releaseTool = new CountDownLatch(1);
            AtomicReference<ToolResult> toolResult = new AtomicReference<>();
            fixture.beforeComplete = () -> {
                toolResult.set(fixture.tools.execute(fixture.tools.scope(KEY),
                        new ToolRequest("fetch_interview_questions", "{}"),
                        fixture.agent.policy("fetch_interview_questions", false), () -> {
                            toolStarted.countDown();
                            try { releaseTool.await(); }
                            catch (InterruptedException interrupted) { toolInterrupted.countDown(); throw interrupted; }
                            return "tool result";
                        }));
                // Represent an AiServices write already in progress when disconnect wins.
                fixture.memories.get(KEY).add(AiMessage.from("late answer"));
            };
            SseEmitter emitter = fixture.start();
            Thread delivery = Thread.ofVirtual().start(() -> fixture.providerHandler.get().onCompleteResponse(RESPONSE));
            try {
                assertThat(toolStarted.await(2, TimeUnit.SECONDS)).isTrue();
                CountDownLatch callbackReturned = new CountDownLatch(1);
                Thread framework = Thread.ofVirtual().start(() -> {
                    ReflectionTestUtils.invokeMethod(emitter, "initializeWithError", new IOException("client disconnected"));
                    callbackReturned.countDown();
                });
                assertThat(callbackReturned.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(toolInterrupted.await(2, TimeUnit.SECONDS))
                        .as("tool cancellation must precede waiting for the provider monitor held by the tool round")
                        .isTrue();
                delivery.join(2_000);
                framework.join(2_000);
                assertThat(framework.isAlive()).isFalse();
                assertThat(delivery.isAlive()).isFalse();
                assertThat(toolResult.get().error().code()).isEqualTo(ToolError.Code.USER_CANCELLED);
                fixture.assertRolledBackAndClosed();
                assertThat(fixture.providers.inFlightCount()).isZero();
            } finally { releaseTool.countDown(); }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final AppProperties properties = new AppProperties();
        private final AgentProperties agent = new AgentProperties();
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final AiTelemetry telemetry = new AiTelemetry(meters, (io.micrometer.tracing.Tracer) null);
        private final AiTelemetry.RequestObservation observation = telemetry.startRequest("sse");
        private final ProviderCallExecutor providers = new ProviderCallExecutor(new ProviderProperties(), telemetry);
        private final BoundedToolRuntime tools = new BoundedToolRuntime(agent, new ObjectMapper(), List.of());
        private final ExecutorService cleanup = Executors.newVirtualThreadPerTaskExecutor();
        private final AiExecutionRegistry executions;
        private final ConversationMemoryRegistry memories;
        private final AiChatController controller;
        private final AtomicReference<StreamingChatResponseHandler> providerHandler = new AtomicReference<>();
        private final CountDownLatch providerStarted = new CountDownLatch(1);
        private final AtomicReference<BiConsumer<PartialResponse, PartialResponseContext>> partial = new AtomicReference<>();
        private final AtomicReference<Consumer<ChatResponse>> complete = new AtomicReference<>();
        private final AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        private Runnable beforeComplete = () -> { };

        private Fixture() {
            properties.getStorage().setEnabled(false);
            executions = new AiExecutionRegistry(Clock.systemUTC(), properties);
            memories = new ConversationMemoryRegistry(properties);
            AiChatService ai = mock(AiChatService.class);
            TokenStream tokenStream = mock(TokenStream.class, RETURNS_SELF);
            when(ai.stream(anyString(), anyString())).thenReturn(tokenStream);
            doAnswer(call -> { partial.set(call.getArgument(0)); return tokenStream; })
                    .when(tokenStream).onPartialResponseWithContext(any());
            doAnswer(call -> { complete.set(call.getArgument(0)); return tokenStream; })
                    .when(tokenStream).onCompleteResponse(any());
            doAnswer(call -> { error.set(call.getArgument(0)); return tokenStream; })
                    .when(tokenStream).onError(any());
            DeadlineStreamingChatModel model = new DeadlineStreamingChatModel(new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    providerHandler.set(handler);
                    providerStarted.countDown();
                }
            }, providers, Duration.ofSeconds(30), Duration.ofSeconds(30));
            doAnswer(call -> {
                model.chat(ChatRequest.builder().messages(UserMessage.from("replacement question")).build(),
                        new StreamingChatResponseHandler() {
                            @Override public void onPartialResponse(PartialResponse value, PartialResponseContext context) {
                                partial.get().accept(value, context);
                            }
                            @Override public void onCompleteResponse(ChatResponse response) {
                                beforeComplete.run();
                                complete.get().accept(response);
                            }
                            @Override public void onError(Throwable failure) { error.get().accept(failure); }
                        });
                return null;
            }).when(tokenStream).start();
            controller = new AiChatController(ai, mock(ClientIdentityService.class), mock(ChatStreamTicketService.class),
                    executions, mock(ConversationMemoryManager.class), memories,
                    new ModelRuntimeInfo("local", "test", "local", "test"), properties, tools, telemetry,
                    providers, new RetrievalProperties(), cleanup);
        }

        private SseEmitter start() throws InterruptedException {
            memories.get(KEY).add(UserMessage.from("original question"));
            memories.get(KEY).add(AiMessage.from("original answer"));
            var snapshot = memories.snapshot(KEY);
            memories.get(KEY).add(UserMessage.from("replacement question"));
            SseEmitter emitter;
            try (var ignored = observation.activate()) {
                emitter = ReflectionTestUtils.invokeMethod(controller, "stream", KEY, "concurrency",
                        "replacement question", snapshot, executions.acquire(KEY), observation);
            }
            assertThat(providerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(meters.get("active.sse.streams").gauge().value()).isEqualTo(1);
            return emitter;
        }

        private void assertRolledBackAndClosed() {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertOriginalMemory();
                assertThat(executions.activeTotal()).isZero();
                assertThat(tools.activeScopes()).isZero();
                assertThat(meters.get("active.sse.streams").gauge().value()).isZero();
                assertThat(meters.get("ai.requests").tags("mode", "sse", "status", "cancelled").counter().count())
                        .isEqualTo(1);
            });
        }

        private void assertOriginalMemory() {
            assertThat(memories.get(KEY).messages()).containsExactly(
                    UserMessage.from("original question"), AiMessage.from("original answer"));
        }

        @Override public void close() {
            cleanup.shutdownNow();
            providers.close();
            tools.close();
            meters.close();
        }
    }
}
