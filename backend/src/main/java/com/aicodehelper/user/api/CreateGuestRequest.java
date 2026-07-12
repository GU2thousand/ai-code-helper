package com.aicodehelper.user.api;

import jakarta.validation.constraints.Size;

public record CreateGuestRequest(
        @Size(max = 40, message = "displayName 最多 40 个字符") String displayName
) {
}
