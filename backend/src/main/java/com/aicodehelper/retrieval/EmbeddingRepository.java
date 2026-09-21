package com.aicodehelper.retrieval;

import java.util.List;

public interface EmbeddingRepository {
    /** Atomically reconcile the complete configured corpus, removing chunks absent from this snapshot. */
    void replaceAll(List<IndexedChunk> chunks);
    List<IndexedChunk> all();
    /** Score is (1 + cosine similarity) / 2, matching LangChain4j's in-memory vector convention. */
    List<ScoredChunk> vectorSearch(float[] queryVector, int limit, double minScore);
    /** Persistent implementations must validate the identity in the same locked transaction as ranking. */
    default List<ScoredChunk> vectorSearch(float[] queryVector, int limit, double minScore, String expectedSnapshotId) {
        if (!EmbeddingSnapshotIdentity.of(all()).equals(expectedSnapshotId)) throw new EmbeddingSnapshotMismatchException();
        return vectorSearch(queryVector, limit, minScore);
    }
    String name();
}
