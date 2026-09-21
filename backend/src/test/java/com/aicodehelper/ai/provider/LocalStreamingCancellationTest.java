package com.aicodehelper.ai.provider;

import com.aicodehelper.ai.local.LocalChatModel;
import com.aicodehelper.ai.local.LocalHashEmbeddingModel;
import com.aicodehelper.ai.local.LocalStreamingChatModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(15)
class LocalStreamingCancellationTest {
    private static final ChatRequest REQUEST = ChatRequest.builder()
            .messages(UserMessage.from("Explain Java Spring dependency injection")).build();

    @Test
    void localWorkerAcknowledgesCancellationInsteadOfSilentlyReturning() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<Throwable> terminalError = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        try (var localWorkers = Executors.newVirtualThreadPerTaskExecutor()) {
            new LocalStreamingChatModel(localWorkers).doChat(REQUEST, new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
                    context.streamingHandle().cancel();
                }
                @Override public void onCompleteResponse(ChatResponse response) { completions.incrementAndGet(); ended.countDown(); }
                @Override public void onError(Throwable error) { terminalError.set(error); ended.countDown(); }
            });
            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(terminalError.get()).isInstanceOf(CancellationException.class);
            assertThat(completions).hasValue(0);
        }
    }

    @Test
    void repeatedActualLocalCancellationsReleaseSharedCapacityWithoutDeliveringLateTerminalCallbacks() throws Exception {
        ProviderProperties properties = new ProviderProperties();
        properties.setMaxInFlight(1);
        try (var localWorkers = Executors.newVirtualThreadPerTaskExecutor();
             var calls = new ProviderCallExecutor(properties)) {
            LocalStreamingChatModel local = new LocalStreamingChatModel(localWorkers);
            for (int attempt = 0; attempt < 12; attempt++) {
                CountDownLatch localEnded = new CountDownLatch(1);
                AtomicReference<Throwable> localTerminalError = new AtomicReference<>();
                AtomicInteger deliveredChunks = new AtomicInteger();
                AtomicInteger deliveredTerminals = new AtomicInteger();

                // This transparent bridge waits for the real local handle's cancellation.
                // Without the wait, the fast local generator could finish before the
                // asynchronous cancel worker runs and conceal the capacity leak.
                StreamingChatModel acknowledgedLocal = new StreamingChatModel() {
                    @Override public void doChat(ChatRequest request, StreamingChatResponseHandler guarded) {
                        local.doChat(request, new StreamingChatResponseHandler() {
                            @Override public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
                                guarded.onPartialResponse(partial, context);
                                await().atMost(Duration.ofSeconds(2)).until(context.streamingHandle()::isCancelled);
                            }
                            @Override public void onCompleteResponse(ChatResponse response) {
                                guarded.onCompleteResponse(response);
                                localEnded.countDown();
                            }
                            @Override public void onError(Throwable error) {
                                localTerminalError.set(error);
                                guarded.onError(error);
                                localEnded.countDown();
                            }
                        });
                    }
                };
                DeadlineStreamingChatModel bounded = new DeadlineStreamingChatModel(acknowledgedLocal, calls,
                        Duration.ofSeconds(3), Duration.ofSeconds(5));
                bounded.chat(REQUEST, new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
                        deliveredChunks.incrementAndGet();
                        context.streamingHandle().cancel();
                    }
                    @Override public void onCompleteResponse(ChatResponse response) { deliveredTerminals.incrementAndGet(); }
                    @Override public void onError(Throwable error) { deliveredTerminals.incrementAndGet(); }
                });

                assertThat(localEnded.await(2, TimeUnit.SECONDS)).as("local cancellation acknowledgement %s", attempt).isTrue();
                assertThat(localTerminalError.get()).isInstanceOf(CancellationException.class);
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(calls.inFlightCount()).isZero());
                assertThat(deliveredChunks).hasValue(1);
                assertThat(deliveredTerminals).hasValue(0);
            }

            // Streaming, chat and embedding share this single permit; cancellation
            // must leave it usable by the other two application workflows.
            assertThat(new DeadlineChatModel(new LocalChatModel(), calls, Duration.ofSeconds(2))
                    .chat("Explain dependency injection")).isNotBlank();
            assertThat(new DeadlineEmbeddingModel(new LocalHashEmbeddingModel(), calls, Duration.ofSeconds(2))
                    .embed("dependency injection").content().vector()).hasSize(384);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(calls.inFlightCount()).isZero());
        }
    }
}
