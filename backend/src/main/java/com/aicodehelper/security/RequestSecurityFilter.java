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

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
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
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        addSecurityHeaders(response);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.info("HTTP {} {} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.remove("requestId");
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
