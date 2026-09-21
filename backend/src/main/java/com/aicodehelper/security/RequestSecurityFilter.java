package com.aicodehelper.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
// Run within Spring's HTTP observation so access logs retain trace correlation.
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestSecurityFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long start = System.nanoTime();
        String requestId = requestId(request);
        String previousRequestId = MDC.get("requestId");
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        if (MDC.get("traceId") != null) response.setHeader("X-Trace-Id", MDC.get("traceId"));
        addSecurityHeaders(response);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // The template excludes stream ticket IDs and arbitrary untrusted paths.
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            log.atInfo().addKeyValue("event", "http.dispatch.completed")
                    .addKeyValue("method", request.getMethod()).addKeyValue("route", route == null ? "unmatched" : route)
                    .addKeyValue("status", response.getStatus()).addKeyValue("dispatch_duration_ms", durationMs)
                    .addKeyValue("async", request.isAsyncStarted()).log("HTTP dispatch completed");
            if (previousRequestId == null) MDC.remove("requestId");
            else MDC.put("requestId", previousRequestId);
        }
    }

    private String requestId(HttpServletRequest request) {
        String supplied = request.getHeader("X-Request-Id");
        return supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
    }

    private void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        response.setHeader("Cache-Control", "no-store");
    }
}
