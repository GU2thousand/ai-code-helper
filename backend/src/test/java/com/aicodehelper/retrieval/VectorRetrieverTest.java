package com.aicodehelper.retrieval;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VectorRetrieverTest {
    @Test
    void passesQueryTypeMetadataToTheEmbeddingProvider() {
        AtomicReference<List<TextSegment>> received = new AtomicReference<>();
        EmbeddingModel model = new EmbeddingModel() {
            @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                received.set(segments);
                return Response.from(List.of(Embedding.from(new float[]{1, 2, 3})));
            }
        };

        assertThat(new VectorRetriever(model).embed("Spring Boot testing")).containsExactly(1, 2, 3);
        assertThat(received.get()).hasSize(1);
        TextSegment query = received.get().getFirst();
        assertThat(query.text()).isEqualTo("Spring Boot testing");
        assertThat(query.metadata().getString(QwenEmbeddingModel.TYPE_KEY)).isEqualTo(QwenEmbeddingModel.TYPE_QUERY);
    }
}
