package com.aicodehelper.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aicodehelper.security.RequestSecurityFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSecurityFilterTest {
    @Test void accessLogUsesRouteTemplateAndRestoresCorrelationContext() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestSecurityFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("requestId", "original-worker-context");
        MDC.put("traceId", "12345678901234567890123456789012");
        try {
            var request = new MockHttpServletRequest("GET", "/api/ai/chat/streams/private-ticket-id");
            request.addHeader("X-Request-Id", "accepted-id");
            request.setQueryString("message=secret-prompt");
            var response = new MockHttpServletResponse();
            new RequestSecurityFilter().doFilter(request, response, (req, res) -> {
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/ai/chat/streams/{streamId}");
                assertThat(MDC.get("requestId")).isEqualTo("accepted-id");
            });
            assertThat(response.getHeader("X-Request-Id")).isEqualTo("accepted-id");
            assertThat(response.getHeader("X-Trace-Id")).isEqualTo("12345678901234567890123456789012");
            assertThat(MDC.get("requestId")).isEqualTo("original-worker-context");
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getKeyValuePairs().toString()).contains("/api/ai/chat/streams/{streamId}", "dispatch_duration_ms")
                    .doesNotContain("private-ticket-id", "secret-prompt");
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
