package com.aicodehelper.evaluation;

import com.aicodehelper.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Evaluation is an operator capability, never inferred from cookies or proxy-supplied IPs. */
@Component
public final class EvaluationAccess {
    private final EvaluationProperties properties;

    public EvaluationAccess(EvaluationProperties properties) { this.properties = properties; }

    public void authorize(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Evaluation endpoints are disabled");
        }
        String expected = properties.getKey();
        String supplied = request.getHeader("X-Evaluation-Key");
        if (expected == null || expected.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVALUATION_KEY_REQUIRED",
                    "Configure APP_EVALUATION_KEY before running evaluations");
        }
        if (supplied == null || supplied.length() > 4096 || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EVALUATION_FORBIDDEN", "Invalid evaluation key");
        }
    }
}
