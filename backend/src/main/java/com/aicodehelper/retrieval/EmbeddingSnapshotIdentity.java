package com.aicodehelper.retrieval;

import com.aicodehelper.ingestion.DocumentLoader;
import java.util.Comparator;
import java.util.List;

/** Identifies both corpus contents and their declared embedding space, independent of row order. */
public final class EmbeddingSnapshotIdentity {
    private EmbeddingSnapshotIdentity() {}
    public static String of(List<IndexedChunk> chunks) {
        StringBuilder value = new StringBuilder();
        for (IndexedChunk indexed : chunks.stream().sorted(Comparator.comparing(c -> c.chunk().id())).toList()) {
            var chunk = indexed.chunk();
            for (String field : List.of(chunk.id(), chunk.source(), chunk.title(), chunk.location(), chunk.text(),
                    chunk.sourceHash(), Integer.toString(chunk.ordinal()), indexed.embeddingModel(), indexed.embeddingVersion(),
                    Integer.toString(indexed.embedding().length))) {
                value.append(field.length()).append(':').append(field);
            }
        }
        return DocumentLoader.hash(value.toString());
    }
}
