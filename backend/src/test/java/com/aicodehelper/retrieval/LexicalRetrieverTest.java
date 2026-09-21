package com.aicodehelper.retrieval;

import com.aicodehelper.ingestion.DocumentChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LexicalRetrieverTest {
    @Test void implementsBm25WithDocumentFrequencyAndLengthNormalization() {
        var chunks = List.of(chunk("a", "java java"), chunk("b", "java spring spring spring"), chunk("c", "database"));
        var results = new LexicalRetriever().search("java", chunks, 3);
        double idf = Math.log(1 + (3 - 2 + 0.5) / (2 + 0.5));
        double averageLength = 7.0 / 3;
        double expected = idf * 2 * 2.2 / (2 + 1.2 * (0.25 + 0.75 * 2 / averageLength));
        assertThat(results).extracting(result -> result.indexedChunk().chunk().id()).containsExactly("a", "b");
        assertThat(results.getFirst().score()).isCloseTo(expected, within(0.00000001));
        assertThat(new LexicalRetriever().search("nonexistent", chunks, 3)).isEmpty();
    }
    @Test void matchesChineseBigramsAndNormalizesWidthAndCase() {
        assertThat(LexicalRetriever.tokens("ＪＡＶＡ 跨域")).contains("java", "跨", "跨域", "域");
    }
    private IndexedChunk chunk(String id, String text) {
        return new IndexedChunk(new DocumentChunk(id, "a.md", "", "a.md", text, "hash", 0), new float[]{1, 0}, "model", "v1");
    }
}
