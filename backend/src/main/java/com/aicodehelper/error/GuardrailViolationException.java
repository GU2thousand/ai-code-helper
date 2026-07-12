package com.aicodehelper.error;

import org.springframework.http.HttpStatus;

public final class GuardrailViolationException extends ApiException {

    public GuardrailViolationException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "GUARDRAIL_REJECTED", message);
    }
}
