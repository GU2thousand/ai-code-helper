package com.aicodehelper.retrieval;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Okapi BM25 (k1=1.2,b=0.75), computed over the current corpus, not PostgreSQL ts_rank. */
public final class LexicalRetriever {
    private static final Pattern TOKENS = Pattern.compile("[\\p{L}\\p{N}+#.]+");
    private static final double K1 = 1.2, B = 0.75;
    public List<ScoredChunk> search(String query, List<IndexedChunk> chunks, int limit) {
        var terms = new LinkedHashSet<>(tokens(query));
        if (terms.isEmpty() || chunks.isEmpty()) return List.of();
        List<Map<String, Integer>> frequencies = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        for (IndexedChunk chunk : chunks) {
            List<String> words = tokens(chunk.chunk().title() + " " + chunk.chunk().text());
            Map<String, Integer> frequency = new HashMap<>();
            for (String word : words) frequency.merge(word, 1, Integer::sum);
            for (String term : terms) if (frequency.containsKey(term)) documentFrequency.merge(term, 1, Integer::sum);
            frequencies.add(frequency); lengths.add(words.size()); totalLength += words.size();
        }
        double averageLength = totalLength / chunks.size();
        List<ScoredChunk> results = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            double score = 0;
            for (String term : terms) {
                int tf = frequencies.get(index).getOrDefault(term, 0);
                if (tf == 0) continue;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
                score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths.get(index) / averageLength));
            }
            if (score > 0) results.add(new ScoredChunk(chunks.get(index), score));
        }
        return results.stream().sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(hit -> hit.indexedChunk().chunk().id())).limit(limit).toList();
    }
    public static List<String> tokens(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        Matcher matcher = TOKENS.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                int[] points = token.codePoints().toArray();
                for (int i = 0; i < points.length; i++) {
                    result.add(new String(points, i, 1));
                    if (i + 1 < points.length) result.add(new String(points, i, 2));
                }
            } else result.add(token);
        }
        return result;
    }
}
