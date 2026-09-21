package com.aicodehelper.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Paragraph and heading aware chunks. Unchanged content keeps its ID when another paragraph is inserted. */
public final class ChunkingStrategy {
    public static final int MAX_CHARACTERS = 1_200;
    public List<DocumentChunk> split(DocumentLoader.SourceDocument document) {
        List<DocumentChunk> result = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        String heading = document.source().replaceFirst("\\.md$", "");
        for (String block : document.text().split("(?:\\r?\\n){2,}")) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#")) {
                heading = trimmed.lines().findFirst().orElse(trimmed).replaceFirst("^#+\\s*", "").trim();
                if (!trimmed.contains("\n")) continue;
            }
            int start = 0;
            while (start < trimmed.length()) {
                int end = Math.min(start + MAX_CHARACTERS, trimmed.length());
                if (end < trimmed.length()) {
                    int boundary = trimmed.lastIndexOf('\n', end);
                    if (boundary > start + 300) end = boundary;
                    // Never split a surrogate pair.
                    if (end > start && Character.isHighSurrogate(trimmed.charAt(end - 1))) end--;
                }
                String text = trimmed.substring(start, end).trim();
                if (!text.isEmpty()) {
                    String contentKey = heading + "\n" + text;
                    int duplicate = occurrences.merge(contentKey, 1, Integer::sum);
                    String id = UUID.nameUUIDFromBytes((document.location() + "\n" + contentKey + "\n" + duplicate)
                            .getBytes(StandardCharsets.UTF_8)).toString();
                    result.add(new DocumentChunk(id, document.source(), heading, document.location(), text,
                            document.sourceHash(), result.size()));
                }
                start = end;
            }
        }
        return List.copyOf(result);
    }
}
