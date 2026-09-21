package com.aicodehelper.retrieval;

import com.aicodehelper.ai.local.LocalHashEmbeddingModel;
import com.aicodehelper.config.AppProperties;
import com.aicodehelper.observability.AiTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RetrievalServiceTest {
    @TempDir Path corpus;
    private AppProperties app;
    private RetrievalProperties config;

    @BeforeEach
    void createCorpus() throws Exception {
        Files.writeString(corpus.resolve("concurrency.md"), """
                # Java synchronization

                Java synchronization uses synchronized monitors to protect shared mutable state from data races.
                """);
        Files.writeString(corpus.resolve("collections.md"), """
                # Java collections

                Java collections include ArrayList for indexed access and HashMap for key value lookups.
                """);
        Files.writeString(corpus.resolve("redis.md"), """
                # Redis persistence

                Redis persistence stores snapshots with RDB and records writes with append only files.
                """);
        app = new AppProperties();
        app.getRag().setLocation(corpus.toUri() + "*.md");
        app.getRag().setMinScore(0);
        config = new RetrievalProperties();
        config.setCandidates(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vector", "lexical", "hybrid", "hybrid_rerank", "hybrid-rerank"})
    void searchesTheActualLocalHashCorpusInEveryModeAndPreservesScoreProvenance(String mode) {
        RetrievalService service = service(null, new DeterministicTokenOverlapReranker());

        RetrievalResult result = service.search("Java synchronization", mode, 3);

        assertThat(result.mode()).isEqualTo(mode.replace('-', '_'));
        assertThat(result.backend()).isEqualTo("memory");
        assertThat(result.embeddingModel()).isEqualTo("local-hash-embedding");
        assertThat(result.embeddingVersion()).isEqualTo("v1");
        assertThat(result.corpusHash()).matches("[0-9a-f]{64}");
        assertThat(result.degraded()).isFalse();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().getFirst().source()).isEqualTo("concurrency.md");
        assertThat(result.timingsMs()).containsKeys("initialization", "fusion", "total");
        assertThat(result.timingsMs().values()).allMatch(value -> value >= 0 && Double.isFinite(value));
        for (int index = 0; index < result.hits().size(); index++) {
            RetrievalHit hit = result.hits().get(index);
            assertThat(hit.finalRank()).isEqualTo(index + 1);
            assertThat(hit.sourceHash()).matches("[0-9a-f]{64}");
            assertThat(hit.location()).startsWith("file:");
            assertThat(hit.text()).isNotBlank();
            double expectedFusion = 0;
            if (hit.lexicalRank() != null) {
                assertThat(hit.lexicalScore()).isPositive();
                expectedFusion += 1.0 / (config.getRrfK() + hit.lexicalRank());
            }
            if (hit.vectorRank() != null) {
                assertThat(hit.vectorScore()).isBetween(0.0, 1.0);
                expectedFusion += 1.0 / (config.getRrfK() + hit.vectorRank());
            }
            assertThat(hit.fusionScore()).isCloseTo(expectedFusion, within(1e-12));
            if (mode.equals("lexical")) assertThat(hit.vectorScore()).isNull();
            if (mode.equals("vector")) assertThat(hit.lexicalScore()).isNull();
            if (mode.contains("rerank")) assertThat(hit.rerankScore()).isNotNull();
            else assertThat(hit.rerankScore()).isNull();
        }
        assertThat(result.reranker()).isEqualTo(mode.contains("rerank") ? "deterministic-token-overlap" : "none");
        assertThat(service.ingestionStatus().sourceCount()).isEqualTo(3);
        assertThat(service.ingestionStatus().chunkCount()).isEqualTo(3);
    }

    @Test
    void vectorDatabaseFailureFallsBackWithExplicitMetadataAndCanRecover() {
        config.setBackend("pgvector");
        config.getPostgres().setRetryBackoff(Duration.ZERO);
        FlakyDatabase database = new FlakyDatabase();
        RetrievalService service = service(database, new DeterministicTokenOverlapReranker());

        RetrievalResult degraded = service.search("Java synchronization", "vector", 3);

        assertThat(degraded.backend()).isEqualTo("memory");
        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.warnings()).containsExactly("vector_database_unavailable_using_memory");
        assertThat(degraded.hits().getFirst().source()).isEqualTo("concurrency.md");
        assertThat(database.searchCalls).isEqualTo(1);
        database.failSearch = false;

        RetrievalResult recovered = service.search("Java synchronization", "vector", 3);

        assertThat(recovered.backend()).isEqualTo("pgvector");
        assertThat(recovered.degraded()).isFalse();
        assertThat(recovered.warnings()).isEmpty();
        assertThat(recovered.hits()).isEqualTo(degraded.hits());
        assertThat(recovered.corpusHash()).isEqualTo(degraded.corpusHash());
        assertThat(database.searchCalls).isEqualTo(2);
    }

    @Test
    void coldStartEmbeddingOutageStillServesRealLexicalEvidenceAndNeverPublishesFakeVectors() {
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        var embeddingCalls = new java.util.concurrent.atomic.AtomicInteger();
        var model = new dev.langchain4j.model.embedding.EmbeddingModel() {
            @Override public dev.langchain4j.model.output.Response<List<dev.langchain4j.data.embedding.Embedding>> embedAll(
                    List<dev.langchain4j.data.segment.TextSegment> segments) {
                embeddingCalls.incrementAndGet();
                if (unavailable.get()) throw new IllegalStateException("provider unavailable");
                return new LocalHashEmbeddingModel().embedAll(segments);
            }
            @Override public String modelName() { return "unavailable-test-provider"; }
        };
        config.setBackend("pgvector");
        config.setEmbeddingRetryBackoff(Duration.ofHours(1));
        FlakyDatabase database = new FlakyDatabase(); database.failSearch = false;
        RetrievalService service = new RetrievalService(model, new PathMatchingResourcePatternResolver(), app, config,
                database, new DeterministicTokenOverlapReranker(), AiTelemetry.noop());

        RetrievalResult lexical = service.search("Java synchronization", "lexical", 3);

        assertThat(lexical.hits().getFirst().source()).isEqualTo("concurrency.md");
        assertThat(lexical.hits().getFirst().text()).contains("synchronized monitors");
        assertThat(lexical.hits()).allMatch(hit -> hit.vectorScore() == null);
        assertThat(lexical.warnings()).containsExactly("embedding_index_unavailable_using_lexical");
        assertThat(lexical.degraded()).isTrue();
        assertThat(service.currentIngestionStatus().embeddingReady()).isFalse();
        assertThat(service.currentIngestionStatus().embedded()).isZero();
        assertThat(database.all()).isEmpty();
        assertThat(service.search("Java synchronization", "hybrid", 3).hits()).isEqualTo(lexical.hits());
        assertThatThrownBy(() -> service.search("Java synchronization", "vector", 3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Embedding index unavailable");
        assertThat(embeddingCalls.get()).isEqualTo(1);

        unavailable.set(false);
        service.reindex();
        assertThat(service.currentIngestionStatus().embeddingReady()).isTrue();
        assertThat(database.all()).hasSize(3).allMatch(chunk -> chunk.embedding().length == 384);
        RetrievalResult recovered = service.search("Java synchronization", "vector", 3);
        assertThat(recovered.degraded()).isFalse();
        assertThat(recovered.backend()).isEqualTo("pgvector");
        assertThat(recovered.corpusHash()).isEqualTo(lexical.corpusHash());
    }

    @Test
    void cancelledBeforeStartRerankTasksReleaseAllConcurrencySlots() throws Exception {
        config.getReranker().setTimeout(Duration.ofNanos(1));
        RetrievalService service = service(null, new DeterministicTokenOverlapReranker());
        for (int i = 0; i < 30; i++) service.search("Java synchronization", "hybrid_rerank", 3);
        config.getReranker().setTimeout(Duration.ofSeconds(2));
        RetrievalResult result = null;
        for (int i = 0; i < 50; i++) {
            result = service.search("Java synchronization", "hybrid_rerank", 3);
            if (!result.degraded()) break;
            Thread.sleep(10);
        }
        assertThat(result).isNotNull();
        assertThat(result.degraded()).isFalse();
        assertThat(result.reranker()).isEqualTo("deterministic-token-overlap");
    }

    @Test
    void peerSnapshotMismatchFallsBackToOwnCorpusWithoutOverwritingThePeer() {
        config.setBackend("pgvector");
        config.getPostgres().setRetryBackoff(Duration.ZERO);
        FlakyDatabase database = new FlakyDatabase();
        database.failSearch = false;
        RetrievalService service = service(database, new DeterministicTokenOverlapReranker());
        RetrievalResult before = service.search("Java synchronization", "hybrid", 3);
        var own = database.all().getFirst();
        IndexedChunk otherModel = new IndexedChunk(own.chunk(), own.embedding(), "another-same-dimension-model", "v2");
        database.replaceAll(List.of(otherModel));

        RetrievalResult mismatch = service.search("Java synchronization", "hybrid", 3);

        assertThat(mismatch.hits()).isEqualTo(before.hits());
        assertThat(mismatch.corpusHash()).isEqualTo(before.corpusHash());
        assertThat(mismatch.embeddingModel()).isEqualTo("local-hash-embedding");
        assertThat(mismatch.backend()).isEqualTo("memory");
        assertThat(mismatch.warnings()).containsExactly("vector_database_snapshot_mismatch_using_memory");
        service.search("Java synchronization", "hybrid", 3);
        assertThat(database.all()).extracting(IndexedChunk::embeddingModel).containsExactly("another-same-dimension-model");
    }

    @Test
    void aSearchCannotMixMemoryOrProvenanceAcrossConcurrentReindex() throws Exception {
        CountDownLatch queryStarted = new CountDownLatch(1), continueQuery = new CountDownLatch(1);
        var shouldBlock = new java.util.concurrent.atomic.AtomicBoolean();
        var model = new dev.langchain4j.model.embedding.EmbeddingModel() {
            @Override public dev.langchain4j.model.output.Response<List<dev.langchain4j.data.embedding.Embedding>> embedAll(
                    List<dev.langchain4j.data.segment.TextSegment> segments) {
                if (shouldBlock.get() && segments.stream().anyMatch(segment -> "query".equals(segment.metadata().getString(
                        dev.langchain4j.community.model.dashscope.QwenEmbeddingModel.TYPE_KEY)))) {
                    queryStarted.countDown();
                    try { if (!continueQuery.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test query timeout"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                }
                return new LocalHashEmbeddingModel().embedAll(segments);
            }
            @Override public String modelName() { return "blocked-local-model"; }
        };
        RetrievalService service = new RetrievalService(model, new PathMatchingResourcePatternResolver(), app, config);
        RetrievalResult before = service.search("Java synchronization", "hybrid", 3);
        shouldBlock.set(true);
        var future = new java.util.concurrent.CompletableFuture<RetrievalResult>();
        Thread.ofVirtual().start(() -> {
            try { future.complete(service.search("Java synchronization", "hybrid", 3)); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        try {
            assertThat(queryStarted.await(2, TimeUnit.SECONDS)).isTrue();
            try (var files = Files.list(corpus)) { for (Path file : files.toList()) Files.delete(file); }
            Files.writeString(corpus.resolve("replacement.md"), "# Replacement\n\nEntirely different knowledge generation.");
            var after = service.reindex();
            assertThat(after.corpusHash()).isNotEqualTo(before.corpusHash());
        } finally { continueQuery.countDown(); }
        RetrievalResult inFlight = future.get(2, TimeUnit.SECONDS);
        assertThat(inFlight.corpusHash()).isEqualTo(before.corpusHash());
        assertThat(inFlight.hits()).isEqualTo(before.hits());
        shouldBlock.set(false);
        assertThat(service.search("knowledge", "hybrid", 3).hits()).extracting(RetrievalHit::source).containsExactly("replacement.md");
    }

    @Test
    void rerankerTimeoutPreservesRrfCandidatesAndScores() {
        config.getReranker().setTimeout(Duration.ofMillis(20));
        Reranker stalled = new Reranker() {
            @Override public List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates) throws Exception {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                return List.of();
            }
            @Override public String name() { return "stalled-test-reranker"; }
        };
        RetrievalService service = service(null, stalled);
        RetrievalResult baseline = service.search("Java synchronization", "hybrid", 3);

        RetrievalResult degraded = service.search("Java synchronization", "hybrid-rerank", 3);

        assertThat(degraded.hits()).isEqualTo(baseline.hits());
        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.warnings()).containsExactly("reranker_timeout_using_rrf");
        assertThat(degraded.reranker()).isEqualTo("rrf-fallback");
        assertThat(degraded.timingsMs()).containsKey("reranker");
    }

    @Test
    void callerCancellationDoesNotBecomeASuccessfulRerankFallback() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Reranker stalled = new Reranker() {
            @Override public List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates) throws Exception {
                started.countDown(); new CountDownLatch(1).await(); return candidates;
            }
            @Override public String name() { return "cancel-test"; }
        };
        RetrievalService service = service(null, stalled);
        service.ingestionStatus();
        var outcome = new java.util.concurrent.CompletableFuture<Throwable>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try { service.search("Java", "hybrid_rerank", 3); outcome.complete(null); }
            catch (Throwable failure) { outcome.complete(failure); }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        assertThat(outcome.get(2, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void rejectsRerankResultsThatDropCandidatesAndKeepsFusionResults() {
        Reranker incomplete = new Reranker() {
            @Override public List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates) {
                return candidates.subList(0, 1);
            }
            @Override public String name() { return "incomplete-test-reranker"; }
        };
        RetrievalService service = service(null, incomplete);
        RetrievalResult baseline = service.search("Java synchronization", "hybrid", 3);

        RetrievalResult result = service.search("Java synchronization", "hybrid_rerank", 3);

        assertThat(result.hits()).isEqualTo(baseline.hits());
        assertThat(result.warnings()).containsExactly("reranker_unavailable_using_rrf");
        assertThat(result.reranker()).isEqualTo("rrf-fallback");
    }

    @Test
    void emptyQueryAndLexicalNoHitReturnEmptyResultsWithoutInventingMatches() {
        RetrievalService service = service(null, new DeterministicTokenOverlapReranker());
        for (String mode : List.of("vector", "lexical", "hybrid", "hybrid-rerank")) {
            RetrievalResult empty = service.search(" \t\n ", mode, 3);
            assertThat(empty.hits()).isEmpty();
            assertThat(empty.degraded()).isFalse();
            assertThat(empty.reranker()).isEqualTo(mode.contains("rerank") ? "deterministic-token-overlap" : "none");
        }
        RetrievalResult noHit = service.search("zzzzunmatchedtokenqqqq", "lexical", 3);
        assertThat(noHit.hits()).isEmpty();
        assertThat(noHit.degraded()).isFalse();
    }

    @Test
    void validatesRequestBoundsAndHonorsConfiguredMode() {
        config.setMode("hybrid-rerank");
        RetrievalService service = service(null, new DeterministicTokenOverlapReranker());
        assertThat(service.defaultMode()).isEqualTo("hybrid_rerank");
        assertThat(service.search("Java", null, 1).mode()).isEqualTo("hybrid_rerank");
        assertThat(service.search("Java", " ", 1).hits()).hasSize(1);
        assertThatThrownBy(() -> service.search(null, "hybrid", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("x".repeat(4001), "hybrid", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("Java", "unknown", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("Java", "hybrid", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("Java", "hybrid", 101)).isInstanceOf(IllegalArgumentException.class);
    }

    private RetrievalService service(EmbeddingRepository primary, Reranker reranker) {
        return new RetrievalService(new LocalHashEmbeddingModel(), new PathMatchingResourcePatternResolver(),
                app, config, primary, reranker, AiTelemetry.noop());
    }

    private static final class FlakyDatabase implements EmbeddingRepository {
        private final InMemoryEmbeddingRepository data = new InMemoryEmbeddingRepository();
        private boolean failSearch = true;
        private int searchCalls;
        @Override public void replaceAll(List<IndexedChunk> chunks) { data.replaceAll(chunks); }
        @Override public List<IndexedChunk> all() { return data.all(); }
        @Override public List<ScoredChunk> vectorSearch(float[] vector, int limit, double minScore) {
            searchCalls++;
            if (failSearch) throw new IllegalStateException("private database connection failure");
            return data.vectorSearch(vector, limit, minScore);
        }
        @Override public String name() { return "pgvector"; }
    }
}
