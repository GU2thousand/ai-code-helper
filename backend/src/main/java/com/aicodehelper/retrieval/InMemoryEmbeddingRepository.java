package com.aicodehelper.retrieval;

import com.aicodehelper.ingestion.EmbeddingIndexer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryEmbeddingRepository implements EmbeddingRepository {
    private volatile Snapshot snapshot = new Snapshot(List.of(), EmbeddingSnapshotIdentity.of(List.of()));
    @Override public void replaceAll(List<IndexedChunk> chunks) {
        Map<String, IndexedChunk> unique = new LinkedHashMap<>();
        int dimensions = chunks.isEmpty() ? 0 : chunks.getFirst().embedding().length;
        for (IndexedChunk chunk : chunks) {
            EmbeddingIndexer.validateVector(chunk.embedding());
            if (chunk.embedding().length != dimensions) throw new IllegalArgumentException("Embedding dimensions must agree");
            unique.put(chunk.chunk().id(), chunk);
        }
        List<IndexedChunk> immutable = List.copyOf(unique.values());
        snapshot = new Snapshot(immutable, EmbeddingSnapshotIdentity.of(immutable));
    }
    @Override public List<IndexedChunk> all() { return snapshot.chunks(); }
    @Override public List<ScoredChunk> vectorSearch(float[] vector, int limit, double minScore) {
        return search(vector, limit, minScore, snapshot);
    }
    @Override public List<ScoredChunk> vectorSearch(float[] vector, int limit, double minScore, String expectedSnapshotId) {
        Snapshot current = snapshot;
        if (!current.identity().equals(expectedSnapshotId)) throw new EmbeddingSnapshotMismatchException();
        return search(vector, limit, minScore, current);
    }
    private List<ScoredChunk> search(float[] vector, int limit, double minScore, Snapshot current) {
        EmbeddingIndexer.validateVector(vector);
        if (limit < 1) return List.of();
        return current.chunks().stream().map(chunk -> new ScoredChunk(chunk, cosineScore(vector, chunk.embedding())))
                .filter(hit -> hit.score() >= minScore)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed().thenComparing(hit -> hit.indexedChunk().chunk().id()))
                .limit(limit).toList();
    }
    private record Snapshot(List<IndexedChunk> chunks, String identity) {}
    public static double cosineScore(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("Query and stored embedding dimensions differ");
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { dot += (double) a[i] * b[i]; aa += (double) a[i] * a[i]; bb += (double) b[i] * b[i]; }
        return Math.max(0, Math.min(1, (1 + dot / Math.sqrt(aa * bb)) / 2));
    }
    @Override public String name() { return "memory"; }
}
