package com.aicodehelper.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Transparent local heuristic baseline. This is deliberately NOT a learned or semantic cross-encoder. */
public final class DeterministicTokenOverlapReranker implements Reranker {
    @Override public List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates) {
        Set<String> terms = new HashSet<>(LexicalRetriever.tokens(query));
        List<RetrievalHit> scored = candidates.stream().map(hit -> HybridRetriever.rank(hit, score(query, terms, hit), hit.finalRank())).sorted(
                Comparator.comparingDouble((RetrievalHit hit) -> hit.rerankScore()).reversed()
                        .thenComparing(RetrievalHit::fusedRank).thenComparing(RetrievalHit::chunkId)).toList();
        List<RetrievalHit> result = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) result.add(HybridRetriever.rank(scored.get(i), scored.get(i).rerankScore(), i + 1));
        return List.copyOf(result);
    }
    private double score(String query, Set<String> terms, RetrievalHit hit) {
        if (terms.isEmpty()) return 0;
        Set<String> body = new HashSet<>(LexicalRetriever.tokens(hit.text()));
        Set<String> title = new HashSet<>(LexicalRetriever.tokens(hit.title()));
        double coverage = terms.stream().filter(body::contains).count() / (double) terms.size();
        double titleCoverage = terms.stream().filter(title::contains).count() / (double) terms.size();
        double phrase = hit.text().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) ? 0.1 : 0;
        return coverage + 0.25 * titleCoverage + phrase;
    }
    @Override public String name() { return "deterministic-token-overlap"; }
}
