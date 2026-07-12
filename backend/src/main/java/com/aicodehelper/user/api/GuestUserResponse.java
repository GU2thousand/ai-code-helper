package com.aicodehelper.user.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuestUserResponse(
        UUID userId,
        String displayName,
        Instant expiresAt,
        String authentication,
        String accessToken
) {
}
