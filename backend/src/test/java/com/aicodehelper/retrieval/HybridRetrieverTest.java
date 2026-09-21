package com.aicodehelper.retrieval;

import com.aicodehelper.ingestion.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HybridRetrieverTest {
    private final HybridRetriever retriever = new HybridRetriever();

    @Test
    void reciprocalRankFusionCombinesRanksWithoutMixingRawScoreScales() {
        IndexedChunk lexicalWinner = chunk(1);
        IndexedChunk consensus = chunk(2);
        IndexedChunk vectorOnly = chunk(3);

        List<RetrievalHit> hits = retriever.fuse(
                List.of(new ScoredChunk(lexicalWinner, 500), new ScoredChunk(consensus, 0.1)),
                List.of(new ScoredChunk(consensus, 0.99), new ScoredChunk(vectorOnly, 0.7)), 60, 10);

        assertThat(hits).extracting(RetrievalHit::chunkId)
                .containsExactly(consensus.chunk().id(), lexicalWinner.chunk().id(), vectorOnly.chunk().id());
        RetrievalHit first = hits.getFirst();
        assertThat(first.fusionScore()).isCloseTo(1.0 / 62 + 1.0 / 61, within(1e-12));
        assertThat(first.lexicalScore()).isEqualTo(0.1);
        assertThat(first.vectorScore()).isEqualTo(0.99);
        assertThat(first.lexicalRank()).isEqualTo(2);
        assertThat(first.vectorRank()).isEqualTo(1);
        assertThat(hits.get(1).fusionScore()).isCloseTo(1.0 / 61, within(1e-12));
        assertThat(hits.get(1).vectorScore()).isNull();
        assertThat(hits.get(2).lexicalScore()).isNull();
        assertThat(hits).extracting(RetrievalHit::fusedRank).containsExactly(1, 2, 3);
        assertThat(hits).extracting(RetrievalHit::finalRank).containsExactly(1, 2, 3);
    }

    @Test
    void deduplicatesWithinAndAcrossChannelsWithoutCountingAnIdTwice() {
        IndexedChunk repeated = chunk(1);
        IndexedChunk other = chunk(2);

        List<RetrievalHit> hits = retriever.fuse(
                List.of(new ScoredChunk(repeated, 9), new ScoredChunk(repeated, 8), new ScoredChunk(other, 7)),
                List.of(new ScoredChunk(repeated, 0.9), new ScoredChunk(repeated, 0.8)), 60, 10);

        assertThat(hits).extracting(RetrievalHit::chunkId).containsExactly(repeated.chunk().id(), other.chunk().id());
        assertThat(hits.getFirst().fusionScore()).isCloseTo(2.0 / 61, within(1e-12));
        assertThat(hits.getFirst().lexicalScore()).isEqualTo(9);
        assertThat(hits.getFirst().vectorScore()).isEqualTo(0.9);
    }

    @Test
    void breaksEqualFusionScoresByChunkIdRegardlessOfChannelInsertionOrder() {
        IndexedChunk lowerId = chunk(1);
        IndexedChunk higherId = chunk(2);
        List<RetrievalHit> first = retriever.fuse(List.of(new ScoredChunk(higherId, 100)),
                List.of(new ScoredChunk(lowerId, 0.8)), 60, 10);
        List<RetrievalHit> reversed = retriever.fuse(List.of(new ScoredChunk(lowerId, 100)),
                List.of(new ScoredChunk(higherId, 0.8)), 60, 10);
        assertThat(first).extracting(RetrievalHit::chunkId).containsExactly(lowerId.chunk().id(), higherId.chunk().id());
        assertThat(reversed).extracting(RetrievalHit::chunkId).containsExactly(lowerId.chunk().id(), higherId.chunk().id());
        assertThat(retriever.fuse(List.of(new ScoredChunk(higherId, 100)),
                List.of(new ScoredChunk(lowerId, 0.8)), 60, 1)).hasSize(1)
                .first().extracting(RetrievalHit::chunkId).isEqualTo(lowerId.chunk().id());
    }

    @Test
    void rerankingPreservesEveryRetrievalScoreAndSourceRank() {
        RetrievalHit original = retriever.fuse(List.of(new ScoredChunk(chunk(1), 2.5)), List.of(), 60, 1).getFirst();
        RetrievalHit ranked = HybridRetriever.rank(original, 0.75, 3);
        assertThat(ranked.rerankScore()).isEqualTo(0.75);
        assertThat(ranked.finalRank()).isEqualTo(3);
        assertThat(ranked).usingRecursiveComparison().ignoringFields("rerankScore", "finalRank").isEqualTo(original);
    }

    @Test
    void acceptsEmptyChannelsAndRejectsInvalidRrfConstant() {
        assertThat(retriever.fuse(List.of(), List.of(), 60, 10)).isEmpty();
        assertThatThrownBy(() -> retriever.fuse(List.of(), List.of(), 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static IndexedChunk chunk(int id) {
        return new IndexedChunk(new DocumentChunk(new UUID(0, id).toString(), "source" + id, "Title " + id,
                "classpath:knowledge/source" + id, "Text " + id, "source-hash", id), new float[] {1, 0}, "test", "v1");
    }
}
