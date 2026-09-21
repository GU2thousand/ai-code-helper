package com.aicodehelper.ingestion;

import com.aicodehelper.retrieval.EmbeddingRepository;
import com.aicodehelper.retrieval.IndexedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.stream.Collectors;

/** Builds a complete snapshot before mutating the repository; failed embedding leaves the old index intact. */
public final class KnowledgeBaseIngestionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIngestionService.class);
    private final DocumentLoader loader;
    private final ChunkingStrategy chunker;
    private final EmbeddingModel model;
    private final String version;
    private volatile IngestionStatus status = new IngestionStatus(0, 0, "", "", "", 0, 0, 0, 0, false);
    public KnowledgeBaseIngestionService(DocumentLoader loader, ChunkingStrategy chunker, EmbeddingModel model, String version) {
        this.loader = loader; this.chunker = chunker; this.model = model; this.version = version;
    }
    public synchronized List<IndexedChunk> ingest(String pattern, EmbeddingRepository repository) {
        return index(prepare(pattern), repository);
    }
    public PreparedCorpus prepare(String pattern) {
        List<DocumentLoader.SourceDocument> documents = loader.load(pattern);
        List<DocumentChunk> chunks = documents.stream().flatMap(document -> chunker.split(document).stream()).toList();
        String corpusHash = DocumentLoader.hash(documents.stream().map(doc -> doc.source() + ":" + doc.sourceHash()).sorted().collect(Collectors.joining("\n")));
        return new PreparedCorpus(chunks, new IngestionStatus(documents.size(), chunks.size(), corpusHash, model.modelName(), version, 0, 0, 0, 0, false));
    }
    public synchronized List<IndexedChunk> index(PreparedCorpus prepared, EmbeddingRepository repository) {
        EmbeddingIndexer.IndexResult indexed = new EmbeddingIndexer(model, version).index(prepared.chunks(), repository.all());
        repository.replaceAll(indexed.chunks());
        status = new IngestionStatus(prepared.status().sourceCount(), indexed.chunks().size(), prepared.status().corpusHash(), model.modelName(), version,
                indexed.embedded(), indexed.reused(), indexed.deleted(), indexed.duplicates(), true);
        log.info("Knowledge index reconciled sourceCount={} chunkCount={} embedded={} reused={} deleted={} duplicates={} model={} version={} corpusHash={}",
                status.sourceCount(), status.chunkCount(), status.embedded(), status.reused(), status.deleted(), status.duplicates(), model.modelName(), version, status.corpusHash());
        return indexed.chunks();
    }
    public IngestionStatus status() { return status; }
    public record IngestionStatus(int sourceCount, int chunkCount, String corpusHash, String embeddingModel,
                                  String embeddingVersion, int embedded, int reused, int deleted, int duplicates,
                                  boolean embeddingReady) {}
    public record PreparedCorpus(List<DocumentChunk> chunks, IngestionStatus status) {}
}
