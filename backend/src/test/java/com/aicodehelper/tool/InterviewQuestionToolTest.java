package com.aicodehelper.tool;

import com.aicodehelper.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewQuestionToolTest {

    @Test
    void extractsUniqueQuestionTitlesWithoutNetworkAccess() {
        InterviewQuestionTool tool = new InterviewQuestionTool(new AppProperties());
        String html = """
                <div class="search-item"><a class="title">Java 中 HashMap 的原理是什么？</a></div>
                <div class="search-item"><a class="title">Java 中 HashMap 的原理是什么？</a></div>
                <article><h3>Spring Bean 的生命周期</h3></article>
                """;

        assertThat(tool.parseQuestions(html, 8)).containsExactly(
                "Java 中 HashMap 的原理是什么？",
                "Spring Bean 的生命周期"
        );
    }
}
