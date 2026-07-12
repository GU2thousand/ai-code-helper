package com.aicodehelper.ai.local;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LocalHashEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSION = 384;

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> embeddings = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            embeddings.add(embedText(segment.text()));
        }
        return Response.from(List.copyOf(embeddings));
    }

    private Embedding embedText(String text) {
        float[] vector = new float[DIMENSION];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        int[] codePoints = normalized.codePoints().toArray();
        if (codePoints.length == 0) {
            vector[0] = 1.0f;
        }
        for (int i = 0; i < codePoints.length; i++) {
            add(vector, Integer.toString(codePoints[i]), 1.0f);
            if (i + 1 < codePoints.length) {
                add(vector, codePoints[i] + ":" + codePoints[i + 1], 1.35f);
            }
            if (i + 2 < codePoints.length) {
                add(vector, codePoints[i] + ":" + codePoints[i + 1] + ":" + codePoints[i + 2], 0.65f);
            }
        }
        for (String word : normalized.split("[^\\p{L}\\p{N}+#.]+")) {
            if (!word.isBlank()) {
                add(vector, "w:" + word, 2.0f);
            }
        }
        Embedding embedding = Embedding.from(vector);
        embedding.normalize();
        return embedding;
    }

    private void add(float[] vector, String token, float weight) {
        int hash = token.hashCode();
        int index = Math.floorMod(hash, vector.length);
        float sign = (hash & 0x40000000) == 0 ? 1.0f : -1.0f;
        vector[index] += sign * weight;
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public String modelName() {
        return "local-hash-embedding";
    }
}
