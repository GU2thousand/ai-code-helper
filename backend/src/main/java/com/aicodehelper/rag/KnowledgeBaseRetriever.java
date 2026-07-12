package com.aicodehelper.rag;

import com.aicodehelper.config.AppProperties;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeBaseRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseRetriever.class);
    private static final int MAX_SEGMENT_CHARACTERS = 1_200;

    private final EmbeddingModel embeddingModel;
    private final ResourcePatternResolver resourceResolver;
    private final AppProperties.Rag config;
    private volatile ContentRetriever delegate;
    private volatile int segmentCount;

    public KnowledgeBaseRetriever(
            EmbeddingModel embeddingModel,
            ResourcePatternResolver resourceResolver,
            AppProperties properties
    ) {
        this.embeddingModel = embeddingModel;
        this.resourceResolver = resourceResolver;
        this.config = properties.getRag();
    }

    @Override
    public List<Content> retrieve(Query query) {
        return delegate().retrieve(query);
    }

    public int segmentCount() {
        return segmentCount;
    }

    private ContentRetriever delegate() {
        ContentRetriever current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (delegate == null) {
                delegate = initialize();
            }
            return delegate;
        }
    }

    private ContentRetriever initialize() {
        List<TextSegment> segments = loadSegments();
        if (segments.isEmpty()) {
            throw new IllegalStateException("No knowledge-base documents were found");
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        store.addAll(embeddings, segments);
        segmentCount = segments.size();
        log.info("Knowledge base initialized documentSegments={} embeddingModel={}",
                segmentCount, embeddingModel.modelName());
        return query -> search(store, query);
    }

    private List<Content> search(InMemoryEmbeddingStore<TextSegment> store, Query query) {
        Embedding queryEmbedding = embeddingModel.embed(toQuerySegment(query.text())).content();
        return store.search(EmbeddingSearchRequest.builder()
                        .query(query.text())
                        .queryEmbedding(queryEmbedding)
                        .maxResults(config.getMaxResults())
                        .minScore(config.getMinScore())
                        .build())
                .matches()
                .stream()
                .map(match -> Content.from(
                        match.embedded(),
                        Map.of(
                                ContentMetadata.SCORE, match.score(),
                                ContentMetadata.EMBEDDING_ID, match.embeddingId()
                        )
                ))
                .toList();
    }

    TextSegment toQuerySegment(String query) {
        Metadata metadata = new Metadata().put(
                QwenEmbeddingModel.TYPE_KEY,
                QwenEmbeddingModel.TYPE_QUERY
        );
        return TextSegment.from(query, metadata);
    }

    private List<TextSegment> loadSegments() {
        try {
            List<TextSegment> result = new ArrayList<>();
            for (Resource resource : resourceResolver.getResources(config.getLocation())) {
                if (!resource.isReadable()) {
                    continue;
                }
                String text;
                try (var input = resource.getInputStream()) {
                    text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                result.addAll(split(resource.getFilename(), configuredLocation(resource.getFilename()), text));
            }
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load the local knowledge base", error);
        }
    }

    String configuredLocation(String filename) {
        String source = filename == null ? "knowledge.md" : filename;
        String pattern = config.getLocation();
        int slash = pattern.lastIndexOf('/');
        if (pattern.contains("*") && slash >= 0) {
            String prefix = pattern.substring(0, slash + 1).replaceFirst("^classpath\\*:", "classpath:");
            return prefix + source;
        }
        return pattern.replaceFirst("^classpath\\*:", "classpath:");
    }

    private List<TextSegment> split(String filename, String location, String markdown) {
        String source = filename == null ? "knowledge.md" : filename;
        String heading = source.replaceFirst("\\.md$", "");
        List<TextSegment> segments = new ArrayList<>();
        for (String block : markdown.split("(?:\\r?\\n){2,}")) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                String firstLine = trimmed.lines().findFirst().orElse(trimmed);
                heading = firstLine.replaceFirst("^#+\\s*", "").trim();
                if (!trimmed.contains("\n")) {
                    continue;
                }
            }
            addChunks(segments, source, location, heading, trimmed);
        }
        return segments;
    }

    private void addChunks(List<TextSegment> target, String source, String location, String title, String text) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + MAX_SEGMENT_CHARACTERS);
            if (end < text.length()) {
                int boundary = text.lastIndexOf('\n', end);
                if (boundary > start + 300) {
                    end = boundary;
                }
            }
            Metadata metadata = new Metadata()
                    .put("source", source)
                    .put("title", title)
                    .put("location", location)
                    .put(QwenEmbeddingModel.TYPE_KEY, QwenEmbeddingModel.TYPE_DOCUMENT);
            target.add(TextSegment.from(text.substring(start, end).trim(), metadata));
            start = end;
        }
    }
}
