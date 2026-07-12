package com.aicodehelper.user.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record VerifyGuestRequest(@NotNull UUID userId) {
}
