package com.aicodehelper.rag;

import com.aicodehelper.config.AppProperties;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseRetrieverTest {

    @Test
    void marksRetrievalInputAsDashScopeQuery() {
        EmbeddingModel unused = segments -> {
            throw new UnsupportedOperationException();
        };
        KnowledgeBaseRetriever retriever = new KnowledgeBaseRetriever(
                unused,
                new PathMatchingResourcePatternResolver(),
                new AppProperties()
        );

        TextSegment query = retriever.toQuerySegment("Spring Boot testing");

        assertThat(query.metadata().getString(QwenEmbeddingModel.TYPE_KEY))
                .isEqualTo(QwenEmbeddingModel.TYPE_QUERY);
    }

    @Test
    void sourceLocationFollowsTheConfiguredKnowledgePattern() {
        AppProperties properties = new AppProperties();
        properties.getRag().setLocation("file:/opt/knowledge/*.md");
        KnowledgeBaseRetriever retriever = new KnowledgeBaseRetriever(
                segments -> {
                    throw new UnsupportedOperationException();
                },
                new PathMatchingResourcePatternResolver(),
                properties
        );

        assertThat(retriever.configuredLocation("guide.md"))
                .isEqualTo("file:/opt/knowledge/guide.md");
    }
}
