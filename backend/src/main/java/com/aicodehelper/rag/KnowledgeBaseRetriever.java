package com.aicodehelper.rag;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.retrieval.RetrievalProperties;
import com.aicodehelper.retrieval.RetrievalService;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeBaseRetriever implements ContentRetriever {
    private final RetrievalService retrieval;
    private final AppProperties.Rag config;
    @Autowired
    public KnowledgeBaseRetriever(RetrievalService retrieval, AppProperties properties) {
        this.retrieval = retrieval; this.config = properties.getRag();
    }
    /** Preserved for existing standalone consumers and metadata tests. */
    public KnowledgeBaseRetriever(EmbeddingModel model, ResourcePatternResolver resolver, AppProperties properties) {
        this(new RetrievalService(model, resolver, properties, new RetrievalProperties()), properties);
    }
    @Override public List<Content> retrieve(Query query) {
        return retrieval.search(query.text(), retrieval.defaultMode(), config.getMaxResults()).hits().stream().map(hit -> {
            Metadata metadata = new Metadata().put("source", hit.source()).put("title", hit.title()).put("location", hit.location())
                    .put("chunk_id", hit.chunkId()).put("source_hash", hit.sourceHash());
            double score = hit.rerankScore() != null ? hit.rerankScore() : hit.fusionScore();
            if (retrieval.defaultMode().equals("vector") && hit.vectorScore() != null) score = hit.vectorScore();
            if (retrieval.defaultMode().equals("lexical") && hit.lexicalScore() != null) score = hit.lexicalScore();
            return Content.from(TextSegment.from("[chunk:" + hit.chunkId() + "]\n" + hit.text(), metadata),
                    Map.of(ContentMetadata.SCORE, score, ContentMetadata.EMBEDDING_ID, hit.chunkId()));
        }).toList();
    }
    public int segmentCount() { return retrieval.currentIngestionStatus().chunkCount(); }
    TextSegment toQuerySegment(String query) {
        return TextSegment.from(query, new Metadata().put(QwenEmbeddingModel.TYPE_KEY, QwenEmbeddingModel.TYPE_QUERY));
    }
    String configuredLocation(String filename) {
        String source = filename == null ? "knowledge.md" : filename;
        String pattern = config.getLocation(); int slash = pattern.lastIndexOf('/');
        if (pattern.contains("*") && slash >= 0) return pattern.substring(0, slash + 1).replaceFirst("^classpath\\*:", "classpath:") + source;
        return pattern.replaceFirst("^classpath\\*:", "classpath:");
    }
}
