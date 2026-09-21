package com.aicodehelper.retrieval;

import com.aicodehelper.ingestion.DocumentChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class InMemoryEmbeddingRepositoryTest {
    @Test void matchesOriginalLangChainCosineScoreAndThresholdConvention() {
        var repository = new InMemoryEmbeddingRepository();
        var chunks = List.of(chunk("same", new float[]{2, 0}), chunk("orthogonal", new float[]{0, 3}), chunk("opposite", new float[]{-1, 0}));
        repository.replaceAll(chunks);
        var original = new InMemoryEmbeddingStore<TextSegment>();
        for (var chunk : chunks) original.add(Embedding.from(chunk.embedding()), TextSegment.from(chunk.chunk().id()));
        var expected = original.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1, 0})).maxResults(3).minScore(0.05).build()).matches();
        var actual = repository.vectorSearch(new float[]{1, 0}, 3, 0.05);
        assertThat(actual).extracting(hit -> hit.indexedChunk().chunk().id()).containsExactlyElementsOf(expected.stream().map(hit -> hit.embedded().text()).toList());
        for (int i = 0; i < actual.size(); i++) assertThat(actual.get(i).score()).isCloseTo(expected.get(i).score(), within(1e-6));
    }
    @Test void deduplicatesSnapshotsAndRejectsInvalidReplacementAtomically() {
        var repository = new InMemoryEmbeddingRepository();
        var one = chunk("one", new float[]{1, 0});
        repository.replaceAll(List.of(one, one));
        assertThat(repository.all()).hasSize(1);
        assertThatThrownBy(() -> repository.replaceAll(List.of(chunk("bad", new float[]{0, 0})))).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.all().getFirst().chunk().id()).isEqualTo("one");
        float[] external = repository.all().getFirst().embedding(); external[0] = 0;
        assertThat(repository.all().getFirst().embedding()[0]).isEqualTo(1);
    }
    private IndexedChunk chunk(String id, float[] vector) {
        return new IndexedChunk(new DocumentChunk(id, "a.md", "title", "a.md", id, "hash", 0), vector, "model", "v1");
    }
}
