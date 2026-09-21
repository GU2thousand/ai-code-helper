package com.aicodehelper.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HybridRetriever {
    public List<RetrievalHit> fuse(List<ScoredChunk> lexical, List<ScoredChunk> vector, int rrfK, int limit) {
        if (rrfK < 1) throw new IllegalArgumentException("RRF k must be positive");
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (int i = 0; i < lexical.size(); i++) {
            ScoredChunk scored = lexical.get(i);
            Candidate c = candidates.computeIfAbsent(scored.indexedChunk().chunk().id(), ignored -> new Candidate(scored.indexedChunk()));
            if (c.lexicalRank == null) { c.lexicalRank = i + 1; c.lexicalScore = scored.score(); c.score += 1.0 / (rrfK + i + 1); }
        }
        for (int i = 0; i < vector.size(); i++) {
            ScoredChunk scored = vector.get(i);
            Candidate c = candidates.computeIfAbsent(scored.indexedChunk().chunk().id(), ignored -> new Candidate(scored.indexedChunk()));
            if (c.vectorRank == null) { c.vectorRank = i + 1; c.vectorScore = scored.score(); c.score += 1.0 / (rrfK + i + 1); }
        }
        List<Candidate> ordered = candidates.values().stream().sorted(Comparator.comparingDouble((Candidate c) -> c.score).reversed()
                .thenComparing(c -> c.chunk.chunk().id())).limit(limit).toList();
        List<RetrievalHit> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Candidate c = ordered.get(i); var chunk = c.chunk.chunk();
            result.add(new RetrievalHit(chunk.id(), chunk.source(), chunk.title(), chunk.location(), chunk.text(), chunk.sourceHash(),
                    c.lexicalScore, c.vectorScore, c.score, null, c.lexicalRank, c.vectorRank, i + 1, i + 1));
        }
        return List.copyOf(result);
    }
    public static RetrievalHit rank(RetrievalHit hit, Double rerankScore, int finalRank) {
        return new RetrievalHit(hit.chunkId(), hit.source(), hit.title(), hit.location(), hit.text(), hit.sourceHash(),
                hit.lexicalScore(), hit.vectorScore(), hit.fusionScore(), rerankScore,
                hit.lexicalRank(), hit.vectorRank(), hit.fusedRank(), finalRank);
    }
    private static final class Candidate {
        final IndexedChunk chunk; Double lexicalScore, vectorScore; Integer lexicalRank, vectorRank; double score;
        Candidate(IndexedChunk chunk) { this.chunk = chunk; }
    }
}
