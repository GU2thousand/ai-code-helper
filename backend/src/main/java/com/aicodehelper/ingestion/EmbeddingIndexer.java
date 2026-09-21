package com.aicodehelper.ingestion;

import com.aicodehelper.retrieval.IndexedChunk;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class EmbeddingIndexer {
    private static final int BATCH_SIZE = 16;
    private final EmbeddingModel model;
    private final String version;
    public EmbeddingIndexer(EmbeddingModel model, String version) { this.model = model; this.version = version; }

    public IndexResult index(List<DocumentChunk> input, List<IndexedChunk> previous) {
        Map<String, IndexedChunk> old = previous.stream().collect(Collectors.toMap(c -> c.chunk().id(), Function.identity(), (a, b) -> a));
        Map<String, DocumentChunk> unique = new LinkedHashMap<>();
        for (DocumentChunk chunk : input) unique.putIfAbsent(chunk.id(), chunk);
        Map<String, IndexedChunk> result = new LinkedHashMap<>();
        List<DocumentChunk> pending = new ArrayList<>();
        for (DocumentChunk chunk : unique.values()) {
            IndexedChunk existing = old.get(chunk.id());
            if (existing != null && model.modelName().equals(existing.embeddingModel()) && version.equals(existing.embeddingVersion())) {
                // Refresh sourceHash/ordinal metadata even when content and embedding are reusable.
                result.put(chunk.id(), new IndexedChunk(chunk, existing.embedding(), model.modelName(), version));
            } else pending.add(chunk);
        }
        for (int offset = 0; offset < pending.size(); offset += BATCH_SIZE) {
            List<DocumentChunk> batch = pending.subList(offset, Math.min(pending.size(), offset + BATCH_SIZE));
            var embeddings = model.embedAll(batch.stream().map(EmbeddingIndexer::segment).toList()).content();
            if (embeddings == null || embeddings.size() != batch.size()) throw new IllegalStateException("Embedding batch count mismatch");
            for (int i = 0; i < batch.size(); i++) {
                float[] vector = embeddings.get(i).vector();
                validateVector(vector);
                result.put(batch.get(i).id(), new IndexedChunk(batch.get(i), vector, model.modelName(), version));
            }
        }
        List<IndexedChunk> ordered = unique.keySet().stream().map(result::get).toList();
        int dimensions = ordered.isEmpty() ? 0 : ordered.getFirst().embedding().length;
        if (ordered.stream().anyMatch(chunk -> chunk.embedding().length != dimensions)) throw new IllegalStateException("Embedding dimension changed without version bump");
        int removed = (int) old.keySet().stream().filter(id -> !unique.containsKey(id)).count();
        return new IndexResult(ordered, pending.size(), unique.size() - pending.size(), removed, input.size() - unique.size());
    }

    public static TextSegment segment(DocumentChunk chunk) {
        return TextSegment.from(chunk.text(), new Metadata().put("source", chunk.source()).put("title", chunk.title())
                .put("location", chunk.location()).put("chunk_id", chunk.id()).put("source_hash", chunk.sourceHash())
                .put(QwenEmbeddingModel.TYPE_KEY, QwenEmbeddingModel.TYPE_DOCUMENT));
    }

    public static void validateVector(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalArgumentException("Embedding must not be empty");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding must be finite");
            norm += value * value;
        }
        if (norm == 0) throw new IllegalArgumentException("Embedding must not be zero");
    }
    public record IndexResult(List<IndexedChunk> chunks, int embedded, int reused, int deleted, int duplicates) {}
}
