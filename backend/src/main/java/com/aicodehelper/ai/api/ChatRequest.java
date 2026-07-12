package com.aicodehelper.ai.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ChatRequest(
        @NotBlank(message = "memoryId 不能为空")
        @Size(max = 128, message = "memoryId 最多 128 个字符")
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "memoryId 只能包含字母、数字、点、下划线和连字符")
        String memoryId,

        @NotBlank(message = "message 不能为空")
        @Size(max = 4_000, message = "message 最多 4000 个字符")
        String message,

        UUID userId,

        Boolean regenerate
) {
}
