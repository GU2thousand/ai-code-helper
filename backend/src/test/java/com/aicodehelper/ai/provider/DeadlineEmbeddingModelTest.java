package com.aicodehelper.ai.provider;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.embedding.listener.EmbeddingModelRequestContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelResponseContext;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Timeout(10)
class DeadlineEmbeddingModelTest {
    private static final Embedding VECTOR = Embedding.from(new float[]{1, 2, 3});

    @Test
    void keepsBatchResponseMetadataModelNameAndDeclaredDimension() {
        Response<List<Embedding>> response = Response.from(List.of(VECTOR), new TokenUsage(4), FinishReason.STOP,
                Map.of("provider-request-id", "embedding-123"));
        AtomicInteger batches = new AtomicInteger();
        EmbeddingModel delegate = new EmbeddingModel() {
            @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) { batches.incrementAndGet(); return response; }
            @Override public int dimension() { return 3; }
            @Override public String modelName() { return "test-embedding"; }
        };
        try (ProviderCallExecutor executor = new ProviderCallExecutor(new ProviderProperties())) {
            DeadlineEmbeddingModel model = new DeadlineEmbeddingModel(delegate, executor, Duration.ofSeconds(2));
            assertThat(model.embedAll(List.of(TextSegment.from("hello")))).isSameAs(response);
            assertThat(model.embed("hello").content()).isEqualTo(VECTOR);
            assertThat(model.embed(TextSegment.from("hello")).tokenUsage()).isEqualTo(new TokenUsage(4));
            assertThat(model.modelName()).isEqualTo("test-embedding");
            assertThat(model.dimension()).isEqualTo(3);
            assertThat(batches.get()).isEqualTo(3);
        }
    }

    @Test
    void defaultDimensionProbeIsAlsoBoundedByDeadline() {
        CountDownLatch release = new CountDownLatch(1);
        EmbeddingModel delegate = segments -> {
            DeadlineChatModelTest.waitIgnoringInterrupt(release);
            return Response.from(List.of(VECTOR));
        };
        ProviderProperties properties = new ProviderProperties();
        properties.setMaxInFlight(1);
        try (ProviderCallExecutor executor = new ProviderCallExecutor(properties)) {
            DeadlineEmbeddingModel model = new DeadlineEmbeddingModel(delegate, executor, Duration.ofMillis(150));
            try {
                assertThatThrownBy(model::dimension).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_TIMEOUT"));
                assertThat(executor.inFlightCount()).isEqualTo(1);
                assertThatThrownBy(() -> model.embed("second")).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_CAPACITY"));
            } finally { release.countDown(); }
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
            assertThat(model.dimension()).isEqualTo(3);
        }
    }

    @Test
    void addedListenersReceiveEachOperationOnceAndKeepModelIdentity() {
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        EmbeddingModel delegate = new EmbeddingModel() {
            @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) { return Response.from(List.of(VECTOR)); }
            @Override public int dimension() { return 3; }
            @Override public String modelName() { return "named-embedding"; }
        };
        try (ProviderCallExecutor executor = new ProviderCallExecutor(new ProviderProperties())) {
            EmbeddingModel model = new DeadlineEmbeddingModel(delegate, executor, Duration.ofSeconds(2))
                    .addListener(first).addListeners(List.of(second));
            assertThat(model).isInstanceOf(DeadlineEmbeddingModel.class);
            assertThat(model.modelName()).isEqualTo("named-embedding");
            assertThat(model.dimension()).isEqualTo(3);
            assertThat(model.embed("hello").content()).isEqualTo(VECTOR);
            assertThat(first.requests.get()).isEqualTo(1);
            assertThat(first.responses.get()).isEqualTo(1);
            assertThat(second.requests.get()).isEqualTo(1);
            assertThat(second.responses.get()).isEqualTo(1);
        }
    }

    @Test
    void addingListenersDoesNotRemoveEmbeddingDeadline() {
        CountDownLatch release = new CountDownLatch(1);
        EmbeddingModel delegate = segments -> {
            DeadlineChatModelTest.waitIgnoringInterrupt(release);
            return Response.from(List.of(VECTOR));
        };
        try (ProviderCallExecutor executor = new ProviderCallExecutor(new ProviderProperties())) {
            EmbeddingModel model = new DeadlineEmbeddingModel(delegate, executor, Duration.ofMillis(150))
                    .addListener(new RecordingListener());
            try {
                assertThatThrownBy(() -> model.embed("slow")).isInstanceOfSatisfying(ProviderCallException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_TIMEOUT"));
            } finally { release.countDown(); }
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(executor.inFlightCount()).isZero());
        }
    }

    private static final class RecordingListener implements EmbeddingModelListener {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger responses = new AtomicInteger();
        @Override public void onRequest(EmbeddingModelRequestContext context) { requests.incrementAndGet(); }
        @Override public void onResponse(EmbeddingModelResponseContext context) { responses.incrementAndGet(); }
    }
}
