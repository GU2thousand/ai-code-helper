package com.aicodehelper.ai.provider;

import com.aicodehelper.observability.AiTelemetry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Timeout(10)
class ProviderAdmissionTest {
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final ChatRequest REQUEST = ChatRequest.builder().messages(UserMessage.from("hello")).build();
    private static final ChatResponse RESPONSE = ChatResponse.builder().aiMessage(AiMessage.from("answer")).build();

    @Test
    void reservationsAreFifoEvenWhenTheSecondWaiterRunsFirstAndTheQueueIsBounded() throws Exception {
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties(2, BUDGET));
             var active = executor.reserve(BUDGET).await()) {
            var first = executor.reserve(BUDGET);
            var second = executor.reserve(BUDGET);
            CountDownLatch secondAwaiting = new CountDownLatch(1);
            CompletableFuture<ProviderCallExecutor.Permit> secondResult = new CompletableFuture<>();
            Thread secondThread = Thread.ofVirtual().start(() -> {
                secondAwaiting.countDown();
                try { secondResult.complete(second.await()); }
                catch (Throwable error) { secondResult.completeExceptionally(error); }
            });
            try {
                assertThat(secondAwaiting.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.queuedCount()).isEqualTo(2);
                assertThat(executor.inFlightCount()).isEqualTo(1);
                assertThatThrownBy(() -> executor.reserve(BUDGET))
                        .isInstanceOfSatisfying(ProviderCallException.class,
                                error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CAPACITY"));

                active.close();
                try (var firstPermit = first.await()) {
                    assertThat(secondResult.isDone()).isFalse();
                    assertThat(executor.queuedCount()).isEqualTo(1);
                    assertThat(executor.inFlightCount()).isEqualTo(1);
                }
                try (var secondPermit = secondResult.get(2, TimeUnit.SECONDS)) {
                    assertThat(executor.queuedCount()).isZero();
                    assertThat(executor.inFlightCount()).isEqualTo(1);
                }
                assertThat(executor.inFlightCount()).isZero();
            } finally {
                first.cancel();
                second.cancel();
                secondThread.interrupt();
                secondThread.join(2_000);
                // A failed assertion may leave an already-claimed second permit unpublished to the test.
                if (secondResult.isDone() && !secondResult.isCompletedExceptionally()) secondResult.join().close();
            }
        }
    }

    @Test
    void cancellingAQueuedHeadRemovesItAndAllowsTheNextReservationToRun() {
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties(2, BUDGET));
             var active = executor.reserve(BUDGET).await()) {
            var cancelled = executor.reserve(BUDGET);
            var next = executor.reserve(BUDGET);
            try {
                cancelled.cancel();
                cancelled.cancel();
                assertThatThrownBy(cancelled::await).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CANCELLED"));
                assertThat(executor.queuedCount()).isEqualTo(1);
                assertThat(executor.inFlightCount()).isEqualTo(1);
                active.close();
                try (var ignored = next.await()) {
                    assertThat(executor.queuedCount()).isZero();
                    assertThat(executor.inFlightCount()).isEqualTo(1);
                }
                assertThat(executor.inFlightCount()).isZero();
            } finally { cancelled.cancel(); next.cancel(); }
        }
    }

    @Test
    void cancellationReturnsAnUnclaimedGrantButCannotReleaseAClaimedPhysicalPermit() {
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties(1, BUDGET))) {
            var unclaimed = executor.reserve(BUDGET);
            assertThat(executor.inFlightCount()).isEqualTo(1);
            unclaimed.cancel();
            unclaimed.cancel();
            assertThat(executor.inFlightCount()).isZero();
            assertThatThrownBy(unclaimed::await).isInstanceOfSatisfying(ProviderCallException.class,
                    error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CANCELLED"));

            var claimed = executor.reserve(BUDGET);
            try (var permit = claimed.await()) {
                claimed.cancel();
                assertThat(executor.inFlightCount()).isEqualTo(1);
            }
            assertThat(executor.inFlightCount()).isZero();
        }
    }

    @Test
    void anExpiredHeadIsRemovedWithoutConsumingTheNextWaitersCapacity() {
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties(2, BUDGET));
             var active = executor.reserve(BUDGET).await()) {
            var expired = executor.reserve(Duration.ofMillis(100));
            var next = executor.reserve(BUDGET);
            try {
                assertThatThrownBy(expired::await).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_TIMEOUT"));
                assertThat(executor.queuedCount()).isEqualTo(1);
                active.close();
                try (var ignored = next.await()) {
                    assertThat(executor.queuedCount()).isZero();
                    assertThat(executor.inFlightCount()).isEqualTo(1);
                }
                assertThat(executor.inFlightCount()).isZero();
            } finally { expired.cancel(); next.cancel(); }
        }
    }

    @Test
    void configuredQueueDeadlineRejectsWithoutStartingProviderWork() {
        AtomicBoolean invoked = new AtomicBoolean();
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties(1, Duration.ofMillis(100)));
             var active = executor.reserve(BUDGET).await()) {
            assertThatThrownBy(() -> executor.call(BUDGET, () -> { invoked.set(true); return "unexpected"; }))
                    .isInstanceOfSatisfying(ProviderCallException.class,
                            error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_QUEUE_TIMEOUT"));
            assertThat(invoked).isFalse();
            assertThat(executor.queuedCount()).isZero();
            assertThat(executor.inFlightCount()).isEqualTo(1);
        }
    }

    @Test
    void callDeadlineCapsQueueWaitingAndAnExpiredCallNeverReachesTheProvider() {
        AtomicBoolean invoked = new AtomicBoolean();
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties(1, BUDGET));
             var active = executor.reserve(BUDGET).await()) {
            assertThatThrownBy(() -> executor.call(Duration.ofMillis(100), () -> {
                invoked.set(true);
                return "unexpected";
            })).isInstanceOfSatisfying(ProviderCallException.class,
                    error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_TIMEOUT"));
            assertThat(invoked).isFalse();
            assertThat(executor.queuedCount()).isZero();
            assertThat(executor.inFlightCount()).isEqualTo(1);
            active.close();
            assertThat(executor.call(BUDGET, () -> "recovered")).isEqualTo("recovered");
            assertThat(invoked).isFalse();
        }
    }

    @Test
    void interruptingAQueuedCallerPreservesItsInterruptFlagAndDoesNotStartTheProvider() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties(1, BUDGET));
             var active = executor.reserve(BUDGET).await()) {
            Thread caller = Thread.ofVirtual().start(() -> {
                try { executor.call(BUDGET, () -> { invoked.set(true); return "unexpected"; }); }
                catch (Throwable error) {
                    failure.set(error);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.queuedCount()).isEqualTo(1));
                caller.interrupt();
                caller.join(2_000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(failure.get()).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CANCELLED"));
                assertThat(interrupted).isTrue();
                assertThat(invoked).isFalse();
                assertThat(executor.queuedCount()).isZero();
                assertThat(executor.inFlightCount()).isEqualTo(1);
            } finally { caller.interrupt(); caller.join(2_000); }
        }
    }

    @Test
    void cancellingAQueuedRequestWakesItsCallerWithoutInterruptingThreadReuse() throws Exception {
        var meters = new SimpleMeterRegistry();
        try {
            AiTelemetry telemetry = new AiTelemetry(meters, (io.micrometer.tracing.Tracer) null);
            var request = telemetry.startRequest("sse");
            AtomicBoolean invoked = new AtomicBoolean();
            AtomicBoolean interrupted = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch cancelledCallFinished = new CountDownLatch(1);
            CountDownLatch reuseCaller = new CountDownLatch(1);
            CompletableFuture<String> reusedResult = new CompletableFuture<>();
            try (var executor = new ProviderCallExecutor(properties(1, BUDGET), telemetry);
                 var active = executor.reserve(BUDGET).await()) {
                Thread caller = Thread.ofVirtual().start(() -> {
                    try {
                        try (var ignored = request.activate()) {
                            try { executor.call(BUDGET, () -> { invoked.set(true); return "unexpected"; }); }
                            catch (Throwable error) { failure.set(error); }
                        }
                        interrupted.set(Thread.currentThread().isInterrupted());
                        cancelledCallFinished.countDown();
                        reuseCaller.await();
                        reusedResult.complete(executor.call(BUDGET, () -> "reused successfully"));
                    } catch (Throwable error) { reusedResult.completeExceptionally(error); }
                });
                try {
                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.queuedCount()).isEqualTo(1));
                    assertThat(meters.get("ai.provider.queued").gauge().value()).isEqualTo(1);
                    assertThat(meters.get("ai.provider.in.flight").gauge().value()).isEqualTo(1);
                    executor.cancelRequest(request);
                    assertThat(cancelledCallFinished.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(failure.get()).isInstanceOfSatisfying(ProviderCallException.class,
                            error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CANCELLED"));
                    assertThat(interrupted).isFalse();
                    assertThat(invoked).isFalse();
                    assertThat(meters.get("ai.provider.queued").gauge().value()).isZero();
                    assertThat(meters.get("ai.provider.queue.wait").tags("status", "cancelled").timer().count()).isEqualTo(1);
                    active.close();
                    reuseCaller.countDown();
                    assertThat(reusedResult.get(2, TimeUnit.SECONDS)).isEqualTo("reused successfully");
                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                            assertThat(meters.get("ai.provider.in.flight").gauge().value()).isZero());
                } finally { reuseCaller.countDown(); caller.interrupt(); caller.join(2_000); }
            } finally { request.finish("cancelled"); }
        } finally { meters.close(); }
    }

    @Test
    void cancellingAnInterruptIgnoringOperationRejectsItsResultAndRetainsCapacityUntilActualExit() throws Exception {
        var meters = new SimpleMeterRegistry();
        try {
            AiTelemetry telemetry = new AiTelemetry(meters, (io.micrometer.tracing.Tracer) null);
            var request = telemetry.startRequest("sse");
            CountDownLatch providerEntered = new CountDownLatch(1);
            CountDownLatch releaseProvider = new CountDownLatch(1);
            CountDownLatch providerExited = new CountDownLatch(1);
            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            try (var executor = new ProviderCallExecutor(properties(1, BUDGET), telemetry)) {
                Thread caller = Thread.ofVirtual().start(() -> {
                    try (var ignored = request.activate()) {
                        executor.call(BUDGET, () -> {
                            providerEntered.countDown();
                            DeadlineChatModelTest.waitIgnoringInterrupt(releaseProvider);
                            providerExited.countDown();
                            return "late result must be suppressed";
                        });
                        outcome.complete(null);
                    } catch (Throwable error) { outcome.complete(error); }
                });
                try {
                    assertThat(providerEntered.await(2, TimeUnit.SECONDS)).isTrue();
                    executor.cancelRequest(request);
                    assertThat(outcome.get(2, TimeUnit.SECONDS)).isInstanceOfSatisfying(ProviderCallException.class,
                            error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CANCELLED"));
                    assertThat(providerExited.getCount()).isEqualTo(1);
                    assertThat(meters.get("ai.provider.in.flight").gauge().value()).isEqualTo(1);
                    var next = executor.reserve(BUDGET);
                    try {
                        assertThat(meters.get("ai.provider.queued").gauge().value()).isEqualTo(1);
                        releaseProvider.countDown();
                        try (var permit = next.await()) {
                            assertThat(providerExited.getCount()).isZero();
                            assertThat(meters.get("ai.provider.queued").gauge().value()).isZero();
                            assertThat(meters.get("ai.provider.in.flight").gauge().value()).isEqualTo(1);
                        }
                        assertThat(meters.get("ai.provider.in.flight").gauge().value()).isZero();
                        assertThat(meters.get("ai.provider.queue.wait").tags("status", "admitted").timer().count()).isEqualTo(1);
                    } finally { next.cancel(); }
                } finally { releaseProvider.countDown(); caller.interrupt(); caller.join(2_000); }
            } finally { request.finish("cancelled"); }
        } finally { meters.close(); }
    }

    @Test
    void shutdownWakesQueuedCallersRejectsNewAdmissionsAndKeepsClaimedPermitsUntilExit() throws Exception {
        ProviderCallExecutor executor = new ProviderCallExecutor(properties(1, BUDGET));
        var active = executor.reserve(BUDGET).await();
        var queued = executor.reserve(BUDGET);
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try (var ignored = queued.await()) { outcome.complete(null); }
            catch (Throwable error) { outcome.complete(error); }
        });
        try {
            executor.close();
            assertThat(outcome.get(2, TimeUnit.SECONDS)).isInstanceOfSatisfying(ProviderCallException.class,
                    error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CANCELLED"));
            assertThat(executor.queuedCount()).isZero();
            assertThat(executor.inFlightCount()).isEqualTo(1);
            assertThatThrownBy(() -> executor.reserve(BUDGET)).isInstanceOf(ProviderCallException.class);
            active.close();
            assertThat(executor.inFlightCount()).isZero();
        } finally {
            active.close();
            queued.cancel();
            executor.close();
            caller.interrupt();
            caller.join(2_000);
        }
    }

    @Test
    void cancellingAQueuedStreamRemovesItBeforeAnyProviderOrDownstreamCallback() {
        AiTelemetry telemetry = new AiTelemetry(null, (io.micrometer.tracing.Tracer) null);
        var request = telemetry.startRequest("sse");
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        StreamingChatModel provider = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                providerCalls.incrementAndGet();
                handler.onCompleteResponse(RESPONSE);
            }
        };
        StreamingChatResponseHandler downstream = new StreamingChatResponseHandler() {
            @Override public void onCompleteResponse(ChatResponse response) { delivered.incrementAndGet(); }
            @Override public void onError(Throwable error) { delivered.incrementAndGet(); }
        };
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties(1, BUDGET), telemetry);
             var active = executor.reserve(BUDGET).await()) {
            var model = new DeadlineStreamingChatModel(provider, executor, BUDGET, BUDGET);
            try (var ignored = request.activate()) { model.chat(REQUEST, downstream); }
            assertThat(executor.queuedCount()).isEqualTo(1);
            executor.cancelRequest(request);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.queuedCount()).isZero());
            active.close();
            assertThat(executor.call(BUDGET, () -> "independent call")).isEqualTo("independent call");
            assertThat(providerCalls).hasValue(0);
            assertThat(delivered).hasValue(0);
        } finally { request.finish("cancelled"); }
    }

    @Test
    void inlineProviderCompletionCanQueueTheNextToolRoundWithoutHoldingUpThePreviousWorker() throws Exception {
        AtomicInteger physicalCalls = new AtomicInteger();
        AtomicInteger maximumPhysicalCalls = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<DeadlineStreamingChatModel> model = new AtomicReference<>();
        CountDownLatch secondRoundEnqueued = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        StreamingChatModel provider = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                maximumPhysicalCalls.accumulateAndGet(physicalCalls.incrementAndGet(), Math::max);
                int round = rounds.incrementAndGet();
                try {
                    handler.onCompleteResponse(RESPONSE);
                    if (round == 1) DeadlineChatModelTest.waitIgnoringInterrupt(releaseFirstWorker);
                } finally { physicalCalls.decrementAndGet(); }
            }
        };
        StreamingChatResponseHandler downstream = new StreamingChatResponseHandler() {
            @Override public void onCompleteResponse(ChatResponse response) {
                if (completions.incrementAndGet() == 1) {
                    model.get().chat(REQUEST, this);
                    secondRoundEnqueued.countDown();
                } else { completed.countDown(); }
            }
            @Override public void onError(Throwable error) { failure.set(error); completed.countDown(); }
        };
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties(1, BUDGET))) {
            model.set(new DeadlineStreamingChatModel(provider, executor, BUDGET, BUDGET));
            model.get().chat(REQUEST, downstream);
            try {
                assertThat(secondRoundEnqueued.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get()).isNull();
                assertThat(rounds).hasValue(1);
                assertThat(executor.inFlightCount()).isEqualTo(1);
                assertThat(executor.queuedCount()).isEqualTo(1);
            } finally { releaseFirstWorker.countDown(); }
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(completions).hasValue(2);
            assertThat(rounds).hasValue(2);
            assertThat(maximumPhysicalCalls).hasValue(1);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(physicalCalls).hasValue(0);
                assertThat(executor.inFlightCount()).isZero();
                assertThat(executor.queuedCount()).isZero();
            });
        } finally { releaseFirstWorker.countDown(); }
    }

    private static ProviderProperties properties(int maxQueued, Duration queueTimeout) {
        ProviderProperties properties = new ProviderProperties();
        properties.setMaxInFlight(1);
        properties.setMaxQueued(maxQueued);
        properties.setQueueTimeout(queueTimeout);
        return properties;
    }
}
