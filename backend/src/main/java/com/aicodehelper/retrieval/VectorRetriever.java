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
        // The single-segment compatibility bridge drops metadata in LangChain4j 1.20.
        // DashScope uses the query marker to distinguish query and document embeddings.
        TextSegment segment = TextSegment.from(query, new Metadata().put(QwenEmbeddingModel.TYPE_KEY, QwenEmbeddingModel.TYPE_QUERY));
        return model.embedAll(List.of(segment)).content().getFirst().vector();
    }
    public List<ScoredChunk> search(float[] vector, EmbeddingRepository repository, int limit, double minScore) {
        return repository.vectorSearch(vector, limit, minScore);
    }
    public List<ScoredChunk> search(float[] vector, EmbeddingRepository repository, int limit, double minScore, String expectedSnapshotId) {
        return repository.vectorSearch(vector, limit, minScore, expectedSnapshotId);
    }
}
