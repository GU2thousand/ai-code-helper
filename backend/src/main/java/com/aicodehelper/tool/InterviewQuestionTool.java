package com.aicodehelper.tool;

import com.aicodehelper.config.AppProperties;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class InterviewQuestionTool {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionTool.class);
    private static final int MAX_RESULTS = 8;

    private final AppProperties.Tools config;

    public InterviewQuestionTool(AppProperties properties) {
        this.config = properties.getTools();
    }

    @Tool(name = "fetch_interview_questions", value = "按技术主题从可信面试题站点检索公开的面试题标题")
    public List<String> fetchInterviewQuestions(
            @P("技术主题，例如 Java、Spring Boot、Vue 3") String topic
    ) {
        String safeTopic = topic == null ? "编程" : topic.strip();
        if (safeTopic.length() > 80) {
            safeTopic = safeTopic.substring(0, 80);
        }
        try {
            Document document = Jsoup.connect(config.getInterviewSearchUrl())
                    .data("query", safeTopic)
                    .data("type", "post")
                    .userAgent("AI-Code-Helper/1.0")
                    .timeout(Math.toIntExact(config.getConnectTimeout().toMillis()))
                    .get();
            return parseQuestions(document, MAX_RESULTS);
        } catch (IOException | RuntimeException error) {
            log.warn("Interview question lookup failed errorType={}", error.getClass().getSimpleName());
            return List.of("当前无法访问面试题来源，请稍后重试");
        }
    }

    List<String> parseQuestions(String html, int limit) {
        return parseQuestions(Jsoup.parse(html), limit);
    }

    private List<String> parseQuestions(Document document, int limit) {
        LinkedHashSet<String> questions = new LinkedHashSet<>();
        for (Element element : document.select(
                ".search-item .title, .content-item-title, .module-body h3, article h2, article h3")) {
            String text = element.text().replaceAll("\\s+", " ").trim();
            if (text.length() >= 4 && text.length() <= 160) {
                questions.add(text);
            }
            if (questions.size() >= limit) {
                break;
            }
        }
        return questions.isEmpty() ? List.of("未找到与该主题匹配的公开面试题") : List.copyOf(questions);
    }
}
