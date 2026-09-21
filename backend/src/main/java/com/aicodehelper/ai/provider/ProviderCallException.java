package com.aicodehelper.ai.provider;

import com.aicodehelper.error.ApiException;
import org.springframework.http.HttpStatus;

/** Stable, sanitized failure contract; upstream messages never become API responses. */
public final class ProviderCallException extends ApiException {
    private ProviderCallException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static ProviderCallException timeout() {
        return new ProviderCallException(HttpStatus.GATEWAY_TIMEOUT, "AI_PROVIDER_TIMEOUT", "模型服务响应超时，请稍后重试");
    }

    public static ProviderCallException capacity() {
        return new ProviderCallException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_CAPACITY", "模型服务繁忙，请稍后重试");
    }

    public static ProviderCallException cancelled() {
        return new ProviderCallException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_CANCELLED", "模型请求已取消");
    }
}
