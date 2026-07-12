package com.aicodehelper.ai.api;

import java.time.Instant;
import java.util.List;

public record RagResponse(
        String memoryId,
        String answer,
        String model,
        List<RagSource> sources,
        Instant createdAt
) {
}
