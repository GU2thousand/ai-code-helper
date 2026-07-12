package com.aicodehelper.error;

import org.springframework.http.HttpStatus;

public final class UpstreamAiException extends ApiException {

    public UpstreamAiException() {
        super(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_ERROR", "AI 服务暂时不可用，请稍后重试");
    }
}
