package com.aicodehelper.ai.local;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalHashEmbeddingModelTest {

    @Test
    void relatedTextScoresHigherThanUnrelatedText() {
        LocalHashEmbeddingModel model = new LocalHashEmbeddingModel();
        float[] query = model.embed("Spring Boot 集成测试").content().vector();
        float[] related = model.embed("如何编写 Spring Boot 的测试").content().vector();
        float[] unrelated = model.embed("Vue 页面颜色和排版").content().vector();

        assertThat(dot(query, related)).isGreaterThan(dot(query, unrelated));
        assertThat(model.dimension()).isEqualTo(384);
    }

    private double dot(float[] left, float[] right) {
        double result = 0;
        for (int index = 0; index < left.length; index++) {
            result += left[index] * right[index];
        }
        return result;
    }
}
