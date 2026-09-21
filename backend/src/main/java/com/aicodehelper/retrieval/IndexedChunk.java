package com.aicodehelper.retrieval;

import com.aicodehelper.ingestion.DocumentChunk;

/** An empty embedding is allowed only for lexical fallback candidates, never inside a vector repository. */
public record IndexedChunk(DocumentChunk chunk, float[] embedding, String embeddingModel, String embeddingVersion) {
    public IndexedChunk { embedding = embedding.clone(); }
    @Override public float[] embedding() { return embedding.clone(); }
}
