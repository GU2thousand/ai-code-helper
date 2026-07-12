package com.aicodehelper.error;

import org.springframework.http.HttpStatus;

public final class StreamTicketException extends ApiException {

    private StreamTicketException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static StreamTicketException notFound() {
        return new StreamTicketException(
                HttpStatus.NOT_FOUND,
                "STREAM_NOT_FOUND",
                "流式会话不存在、已过期或已被使用"
        );
    }

    public static StreamTicketException wrongOwner() {
        return new StreamTicketException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_STREAM_OWNER",
                "当前会话无权使用该流式票据"
        );
    }

    public static StreamTicketException capacityReached() {
        return new StreamTicketException(
                HttpStatus.TOO_MANY_REQUESTS,
                "STREAM_CAPACITY_REACHED",
                "当前流式请求较多，请稍后重试"
        );
    }
}
