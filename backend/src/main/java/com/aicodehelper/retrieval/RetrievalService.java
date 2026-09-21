package com.aicodehelper.retrieval;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.ingestion.ChunkingStrategy;
import com.aicodehelper.ingestion.DocumentLoader;
import com.aicodehelper.ingestion.KnowledgeBaseIngestionService;
import com.aicodehelper.observability.AiTelemetry;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class RetrievalService {
    private static final Set<String> MODES = Set.of("vector", "lexical", "hybrid", "hybrid_rerank");
    private static final ExecutorService RERANK_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private final RetrievalProperties config;
    private final AppProperties.Rag rag;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingRepository primary;
    private final KnowledgeBaseIngestionService ingestion;
    private final LexicalRetriever lexical = new LexicalRetriever();
    private final VectorRetriever vector;
    private final HybridRetriever fusion = new HybridRetriever();
    private final Reranker reranker;
    private final AiTelemetry telemetry;
    private final Semaphore rerankSlots = new Semaphore(4);
    private volatile IndexSnapshot index;
    private volatile boolean databaseHealthy;
    private volatile boolean databaseSnapshotMismatch;
    private volatile long databaseRetryAt;
    private volatile long embeddingRetryAt;

    @Autowired
    public RetrievalService(EmbeddingModel embeddingModel, ResourcePatternResolver resolver, AppProperties properties,
                            RetrievalProperties config, AiTelemetry telemetry) {
        this(embeddingModel, resolver, properties, config,
                config.getBackend().equals("pgvector") ? new PgVectorEmbeddingRepository(config.getPostgres()) : null,
                configuredReranker(config), telemetry);
    }
    public RetrievalService(EmbeddingModel embeddingModel, ResourcePatternResolver resolver, AppProperties properties, RetrievalProperties config) {
        this(embeddingModel, resolver, properties, config,
                config.getBackend().equals("pgvector") ? new PgVectorEmbeddingRepository(config.getPostgres()) : null,
                configuredReranker(config), AiTelemetry.noop());
    }
    public RetrievalService(EmbeddingModel embeddingModel, ResourcePatternResolver resolver, AppProperties properties,
                            RetrievalProperties config, EmbeddingRepository primary, Reranker reranker, AiTelemetry telemetry) {
        validate(config);
        this.config = config; this.rag = properties.getRag(); this.embeddingModel = embeddingModel;
        this.primary = primary; this.reranker = reranker; this.telemetry = telemetry;
        ingestion = new KnowledgeBaseIngestionService(new DocumentLoader(resolver), new ChunkingStrategy(), embeddingModel, config.getEmbeddingVersion());
        vector = new VectorRetriever(embeddingModel);
    }

    public RetrievalResult search(String query, String mode, int k) {
        if (query == null || query.length() > 4_000) throw new IllegalArgumentException("Query must contain at most 4000 characters");
        if (k < 1 || k > 100) throw new IllegalArgumentException("k must be between 1 and 100");
        String selectedMode = normalizeMode(mode == null || mode.isBlank() ? config.getMode() : mode);
        String normalized = query.strip().replaceAll("\\s+", " ");
        Map<String, Double> timings = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        long totalStart = System.nanoTime();
        try (var total = telemetry.stage("retrieval"); var scope = total.activate()) {
            try {
                long initStart = System.nanoTime();
                ensureInitialized();
                recoverDatabase();
                // A published repository is never mutated: all stages and provenance belong to this generation.
                IndexSnapshot snapshot = index;
                InMemoryEmbeddingRepository memory = snapshot.memory();
                timings.put("initialization", elapsed(initStart));
                String backendUsed = repository(snapshot).name();
                if (!snapshot.status().embeddingReady()) warnings.add("embedding_index_unavailable_using_lexical");
                else if (primary != null && !databaseHealthy) warnings.add(databaseWarning());
                List<ScoredChunk> lexicalHits = List.of(), vectorHits = List.of();
                int candidateCount = Math.max(k, config.getCandidates());
                if (!normalized.isBlank() && !selectedMode.equals("vector")) {
                    long start = System.nanoTime();
                    try (var stage = telemetry.stage("lexical")) { lexicalHits = lexical.search(normalized, snapshot.lexicalChunks(), candidateCount); }
                    timings.put("lexical", elapsed(start));
                }
                if (!normalized.isBlank() && selectedMode.equals("vector") && !snapshot.status().embeddingReady())
                    throw new IllegalStateException("Embedding index unavailable; retry later or use lexical retrieval");
                if (!normalized.isBlank() && !selectedMode.equals("lexical") && snapshot.status().embeddingReady()) {
                    long start = System.nanoTime();
                    try (var stage = telemetry.stage("vector")) {
                        try {
                            float[] queryVector = vector.embed(normalized);
                            EmbeddingRepository searchRepository = repository(snapshot);
                            backendUsed = searchRepository.name();
                            try { vectorHits = vector.search(queryVector, searchRepository, candidateCount, rag.getMinScore(), snapshot.identity()); }
                            catch (RuntimeException failure) {
                                if (searchRepository == memory) throw failure;
                                if (failure instanceof EmbeddingSnapshotMismatchException) {
                                    // A request straddling a local reindex uses its old immutable fallback without
                                    // poisoning the new generation. A peer mismatch stays in memory until reindex.
                                    markSnapshotMismatch(snapshot);
                                    warnings.add("vector_database_snapshot_mismatch_using_memory");
                                } else {
                                    markDatabaseUnavailable();
                                    warnings.add("vector_database_unavailable_using_memory");
                                }
                                vectorHits = vector.search(queryVector, memory, candidateCount, rag.getMinScore());
                                backendUsed = memory.name();
                                stage.failure(failure);
                            }
                        } catch (RuntimeException failure) {
                            stage.failure(failure);
                            if (selectedMode.equals("vector")) throw failure;
                            warnings.add("vector_search_unavailable_using_lexical");
                        }
                    }
                    timings.put("vector", elapsed(start));
                }
                long fusionStart = System.nanoTime();
                List<RetrievalHit> hits = fusion.fuse(lexicalHits, vectorHits, config.getRrfK(), candidateCount);
                timings.put("fusion", elapsed(fusionStart));
                String rerankerName = selectedMode.equals("hybrid_rerank") ? reranker.name() : "none";
                if (selectedMode.equals("hybrid_rerank") && !hits.isEmpty()) {
                    long start = System.nanoTime();
                    try (var stage = telemetry.stage("reranker")) {
                        try { hits = boundedRerank(normalized, hits.stream().limit(30).toList()); rerankerName = reranker.name(); }
                        catch (InterruptedException failure) {
                            stage.failure(failure);
                            throw new java.util.concurrent.CancellationException("Retrieval reranking was cancelled");
                        } catch (Exception failure) {
                            stage.failure(failure);
                            warnings.add(isTimeout(failure) ? "reranker_timeout_using_rrf" : "reranker_unavailable_using_rrf");
                            rerankerName = "rrf-fallback";
                        }
                    }
                    timings.put("reranker", elapsed(start));
                }
                if (hits.isEmpty()) telemetry.noHit();
                timings.put("total", elapsed(totalStart));
                return new RetrievalResult(query, selectedMode, backendUsed, snapshot.status().embeddingModel(), snapshot.status().embeddingVersion(),
                        snapshot.status().corpusHash(), rerankerName, rag.getMinScore(), !warnings.isEmpty(), List.copyOf(new java.util.LinkedHashSet<>(warnings)),
                        Map.copyOf(timings), hits.stream().limit(k).toList());
            } catch (RuntimeException failure) { total.failure(failure); throw failure; }
        }
    }

    public String defaultMode() { return normalizeMode(config.getMode()); }
    /** Health reporting must not initialize embeddings or make provider calls. */
    public KnowledgeBaseIngestionService.IngestionStatus currentIngestionStatus() {
        IndexSnapshot snapshot = index;
        return snapshot == null ? new KnowledgeBaseIngestionService.IngestionStatus(0, 0, "", "", "", 0, 0, 0, 0, false) : snapshot.status();
    }
    public KnowledgeBaseIngestionService.IngestionStatus ingestionStatus() { ensureInitialized(); return index.status(); }
    /** Re-read the configured corpus; deletes removed chunks and reuses unchanged embeddings. */
    public synchronized KnowledgeBaseIngestionService.IngestionStatus reindex() {
        InMemoryEmbeddingRepository memory = new InMemoryEmbeddingRepository();
        if (index != null) memory.replaceAll(index.memory().all());
        else if (primary != null) {
            try { memory.replaceAll(primary.all()); }
            catch (RuntimeException failure) { markDatabaseUnavailable(); }
        }
        var prepared = ingestion.prepare(rag.getLocation());
        try { ingestion.index(prepared, memory); }
        catch (RuntimeException failure) {
            // Keep an existing complete generation intact on failed reindex. Cold starts can still
            // serve BM25 from raw documents; empty embeddings never enter a vector repository.
            if (index != null && index.status().embeddingReady()) throw failure;
            List<IndexedChunk> raw = prepared.chunks().stream().map(chunk -> new IndexedChunk(chunk, new float[0],
                    prepared.status().embeddingModel(), prepared.status().embeddingVersion())).toList();
            index = new IndexSnapshot(new InMemoryEmbeddingRepository(), raw, prepared.status(), "");
            embeddingRetryAt = System.nanoTime() + config.getEmbeddingRetryBackoff().toNanos();
            databaseHealthy = false;
            return index.status();
        }
        IndexSnapshot next = new IndexSnapshot(memory, memory.all(), ingestion.status(), EmbeddingSnapshotIdentity.of(memory.all()));
        if (primary != null) {
            try { primary.replaceAll(memory.all()); databaseSnapshotMismatch = false; databaseHealthy = true; }
            catch (RuntimeException failure) { markDatabaseUnavailable(); }
        }
        index = next;
        return next.status();
    }
    private void ensureInitialized() {
        if (index == null) synchronized (this) { if (index == null) reindex(); }
        else if (!index.status().embeddingReady() && System.nanoTime() >= embeddingRetryAt) {
            synchronized (this) {
                if (!index.status().embeddingReady() && System.nanoTime() >= embeddingRetryAt) reindex();
            }
        }
    }
    private void recoverDatabase() {
        if (primary == null || !index.status().embeddingReady() || databaseHealthy || databaseSnapshotMismatch || System.nanoTime() < databaseRetryAt) return;
        synchronized (this) {
            if (databaseHealthy || databaseSnapshotMismatch || System.nanoTime() < databaseRetryAt) return;
            try { primary.replaceAll(index.memory().all()); databaseHealthy = true; }
            catch (RuntimeException failure) { markDatabaseUnavailable(); }
        }
    }
    private void markDatabaseUnavailable() {
        databaseHealthy = false;
        databaseRetryAt = System.nanoTime() + config.getPostgres().getRetryBackoff().toNanos();
    }
    private synchronized void markSnapshotMismatch(IndexSnapshot snapshot) {
        if (index == snapshot) { databaseSnapshotMismatch = true; databaseHealthy = false; }
    }
    private EmbeddingRepository repository(IndexSnapshot snapshot) {
        return primary != null && snapshot.status().embeddingReady() && databaseHealthy ? primary : snapshot.memory();
    }
    private String databaseWarning() {
        return databaseSnapshotMismatch ? "vector_database_snapshot_mismatch_using_memory" : "vector_database_unavailable_using_memory";
    }
    private record IndexSnapshot(InMemoryEmbeddingRepository memory, List<IndexedChunk> lexicalChunks,
                                 KnowledgeBaseIngestionService.IngestionStatus status, String identity) {}
    private List<RetrievalHit> boundedRerank(String query, List<RetrievalHit> candidates) throws Exception {
        if (!rerankSlots.tryAcquire()) throw new IllegalStateException("Reranker concurrency limit reached");
        var future = new java.util.concurrent.FutureTask<List<RetrievalHit>>(() -> reranker.rerank(query, candidates)) {
            @Override public void run() {
                try { super.run(); }
                finally { rerankSlots.release(); }
            }
        };
        try { RERANK_EXECUTOR.execute(future); }
        catch (RuntimeException rejected) { rerankSlots.release(); throw rejected; }
        try {
            List<RetrievalHit> result = future.get(config.getReranker().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (result == null || result.size() != candidates.size()
                    || !result.stream().map(RetrievalHit::chunkId).collect(java.util.stream.Collectors.toSet())
                    .equals(candidates.stream().map(RetrievalHit::chunkId).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalStateException("Reranker must return each candidate once");
            return result;
        } catch (InterruptedException failure) {
            future.cancel(true); Thread.currentThread().interrupt(); throw failure;
        } catch (TimeoutException failure) { future.cancel(true); throw failure; }
        catch (ExecutionException failure) { throw new IllegalStateException("Reranker failed", failure.getCause()); }
    }
    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) return true;
        return false;
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static Reranker configuredReranker(RetrievalProperties config) {
        return switch (config.getReranker().getType()) {
            case "deterministic-token-overlap" -> new DeterministicTokenOverlapReranker();
            case "http-cross-encoder" -> new HttpCrossEncoderReranker(config.getReranker());
            default -> throw new IllegalArgumentException("Unknown reranker type");
        };
    }
    private static String normalizeMode(String mode) {
        String normalized = mode.replace('-', '_');
        if (!MODES.contains(normalized)) throw new IllegalArgumentException("Unknown retrieval mode");
        return normalized;
    }
    private static void validate(RetrievalProperties config) {
        normalizeMode(config.getMode());
        if (!Set.of("memory", "pgvector").contains(config.getBackend())) throw new IllegalArgumentException("Unknown retrieval backend");
        if (config.getCandidates() < 1 || config.getCandidates() > 100 || config.getRrfK() < 1)
            throw new IllegalArgumentException("Invalid retrieval candidate or RRF limit");
        if (config.getEmbeddingVersion() == null || config.getEmbeddingVersion().isBlank()) throw new IllegalArgumentException("Embedding version is required");
        if (config.getEmbeddingRetryBackoff() == null || config.getEmbeddingRetryBackoff().isNegative()) throw new IllegalArgumentException("Embedding retry backoff must not be negative");
        if (config.getReranker().getTimeout().isNegative() || config.getReranker().getTimeout().isZero()) throw new IllegalArgumentException("Reranker timeout must be positive");
    }
}
