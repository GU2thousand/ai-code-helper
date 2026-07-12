package com.aicodehelper.error;

import org.springframework.http.HttpStatus;

public final class GuestAuthenticationException extends ApiException {

    public GuestAuthenticationException(String message) {
        super(HttpStatus.UNAUTHORIZED, "INVALID_GUEST_SESSION", message);
    }
}
