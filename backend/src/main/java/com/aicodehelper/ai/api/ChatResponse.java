package com.aicodehelper.ai.api;

import java.time.Instant;

public record ChatResponse(
        String memoryId,
        String answer,
        String model,
        Instant createdAt
) {
}
