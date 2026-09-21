package com.aicodehelper.retrieval;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.List;

public final class VectorRetriever {
    private final EmbeddingModel model;
    public VectorRetriever(EmbeddingModel model) { this.model = model; }
    public float[] embed(String query) {
        return model.embed(TextSegment.from(query, new Metadata().put(QwenEmbeddingModel.TYPE_KEY, QwenEmbeddingModel.TYPE_QUERY))).content().vector();
    }
    public List<ScoredChunk> search(float[] vector, EmbeddingRepository repository, int limit, double minScore) {
        return repository.vectorSearch(vector, limit, minScore);
    }
    public List<ScoredChunk> search(float[] vector, EmbeddingRepository repository, int limit, double minScore, String expectedSnapshotId) {
        return repository.vectorSearch(vector, limit, minScore, expectedSnapshotId);
    }
}
