package com.aicodehelper.evaluation;

import com.aicodehelper.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class EvaluationAccessTest {
    @Test
    void disabledOrUnconfiguredEvaluationCannotBeEnabledByARequest() {
        EvaluationProperties properties = new EvaluationProperties();
        EvaluationAccess access = new EvaluationAccess(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Evaluation-Key", "test-only");
        assertThatThrownBy(() -> access.authorize(request)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("NOT_FOUND");
        properties.setEnabled(true);
        assertThatThrownBy(() -> access.authorize(request)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("EVALUATION_KEY_REQUIRED");
    }

    @Test
    void requiresKeyEvenForLoopbackAndForgedProxyHeaders() {
        EvaluationProperties properties = new EvaluationProperties();
        properties.setEnabled(true);
        properties.setKey("test-only");
        EvaluationAccess access = new EvaluationAccess(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThatThrownBy(() -> access.authorize(request)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("EVALUATION_FORBIDDEN");
        request.addHeader("X-Evaluation-Key", "test-only");
        assertThatCode(() -> access.authorize(request)).doesNotThrowAnyException();
    }
}
