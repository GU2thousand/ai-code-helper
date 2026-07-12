package com.aicodehelper.user.api;

import java.util.UUID;

public record VerifyGuestResponse(UUID userId, boolean valid) {
}
