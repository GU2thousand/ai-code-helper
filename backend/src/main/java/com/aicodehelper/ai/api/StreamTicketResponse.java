package com.aicodehelper.ai.api;

import java.time.Instant;
import java.util.UUID;

public record StreamTicketResponse(
        UUID streamId,
        String streamUrl,
        Instant expiresAt
) {
}
