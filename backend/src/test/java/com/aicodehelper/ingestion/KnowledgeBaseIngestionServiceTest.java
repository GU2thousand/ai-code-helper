package com.aicodehelper.ingestion;

import com.aicodehelper.ai.local.LocalHashEmbeddingModel;
import com.aicodehelper.retrieval.InMemoryEmbeddingRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseIngestionServiceTest {
    @TempDir Path directory;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Test void repeatedIngestionReusesEmbeddingsAndContentChangesDeleteOldChunks() throws Exception {
        Path file = directory.resolve("guide.md");
        Files.writeString(file, "# Java\n\nImmutable records store values.\n\nVirtual threads support blocking IO.");
        CountingModel model = new CountingModel();
        var repository = new InMemoryEmbeddingRepository();
        var ingestion = ingestion(model, "v1");
        ingestion.ingest(pattern(), repository);
        var original = repository.all();
        String originalHash = ingestion.status().corpusHash();
        assertThat(model.count.get()).isEqualTo(2);
        ingestion.ingest(pattern(), repository);
        assertThat(model.count.get()).isEqualTo(2);
        assertThat(ingestion.status().reused()).isEqualTo(2);
        assertThat(repository.all()).extracting(chunk -> chunk.chunk().id()).containsExactlyElementsOf(original.stream().map(chunk -> chunk.chunk().id()).toList());

        Files.writeString(file, "# Java\n\nNew paragraph is inserted first.\n\nImmutable records store values.");
        ingestion.ingest(pattern(), repository);
        assertThat(model.count.get()).isEqualTo(3);
        assertThat(ingestion.status().deleted()).isEqualTo(1);
        assertThat(ingestion.status().reused()).isEqualTo(1);
        assertThat(ingestion.status().corpusHash()).isNotEqualTo(originalHash);
        assertThat(repository.all().get(1).chunk().id()).isEqualTo(original.getFirst().chunk().id());
        assertThat(repository.all().get(1).chunk().sourceHash()).isNotEqualTo(original.getFirst().chunk().sourceHash());
        assertThat(repository.all()).noneMatch(chunk -> chunk.chunk().text().contains("Virtual threads"));

        Files.delete(file);
        ingestion.ingest(pattern(), repository);
        assertThat(repository.all()).isEmpty();
        assertThat(ingestion.status().deleted()).isEqualTo(2);
    }

    @Test void versionBumpReembedsStableIdsAndFailedEmbeddingPreservesOldSnapshot() throws Exception {
        Path file = directory.resolve("guide.md");
        Files.writeString(file, "# Java\n\nImmutable record.");
        CountingModel model = new CountingModel();
        var repository = new InMemoryEmbeddingRepository();
        ingestion(model, "v1").ingest(pattern(), repository);
        String id = repository.all().getFirst().chunk().id();
        ingestion(model, "v2").ingest(pattern(), repository);
        assertThat(model.count.get()).isEqualTo(2);
        assertThat(repository.all().getFirst().embeddingVersion()).isEqualTo("v2");
        assertThat(repository.all().getFirst().chunk().id()).isEqualTo(id);
        model.fail = true;
        Files.writeString(file, "# Java\n\nChanged source text.");
        assertThatThrownBy(() -> ingestion(model, "v2").ingest(pattern(), repository)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.all().getFirst().chunk().id()).isEqualTo(id);
    }

    @Test void duplicateInputIsIndexedOnceAndRepeatedParagraphsRemainDistinguishable() {
        var document = new DocumentLoader.SourceDocument("a.md", "classpath:knowledge-base/a.md", "# Heading\n\nsame text\n\nsame text", "hash");
        var chunks = new ChunkingStrategy().split(document);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().id()).isNotEqualTo(chunks.getLast().id());
        CountingModel model = new CountingModel();
        var result = new EmbeddingIndexer(model, "v1").index(List.of(chunks.getFirst(), chunks.getFirst()), List.of());
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(model.count.get()).isEqualTo(1);
    }

    @Test void classpathLocationsAreIndependentOfBuildDirectory() {
        var documents = new DocumentLoader(resolver).load("classpath*:knowledge-base/*.md");
        assertThat(documents).isNotEmpty().allMatch(doc -> doc.location().equals("classpath:knowledge-base/" + doc.source()));
    }

    private String pattern() { return directory.toUri() + "*.md"; }
    private KnowledgeBaseIngestionService ingestion(EmbeddingModel model, String version) {
        return new KnowledgeBaseIngestionService(new DocumentLoader(resolver), new ChunkingStrategy(), model, version);
    }
    private static final class CountingModel implements EmbeddingModel {
        final AtomicInteger count = new AtomicInteger(); boolean fail;
        @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            if (fail) throw new IllegalStateException("provider unavailable");
            count.addAndGet(segments.size()); return new LocalHashEmbeddingModel().embedAll(segments);
        }
        @Override public String modelName() { return "test-counting"; }
    }
}
