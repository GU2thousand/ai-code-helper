package com.aicodehelper.ai.api;

public record RagSource(
        String title,
        String source,
        String location,
        String excerpt,
        Double score
) {
}
