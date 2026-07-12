package com.aicodehelper.error;

import org.springframework.http.HttpStatus;

public final class AiExecutionRejectedException extends ApiException {

    private AiExecutionRejectedException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static AiExecutionRejectedException conversationBusy() {
        return new AiExecutionRejectedException(
                HttpStatus.CONFLICT,
                "CONVERSATION_BUSY",
                "该会话已有回复正在生成，请等待完成后再试"
        );
    }

    public static AiExecutionRejectedException capacityReached() {
        return new AiExecutionRejectedException(
                HttpStatus.TOO_MANY_REQUESTS,
                "AI_CAPACITY_REACHED",
                "AI 请求过于频繁，请稍后重试"
        );
    }

    public static AiExecutionRejectedException memoryCapacityReached() {
        return new AiExecutionRejectedException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_MEMORY_CAPACITY_REACHED",
                "会话记忆容量已满，请稍后重试或迁移到持久化存储"
        );
    }
}
