package com.aicodehelper.user;

import com.aicodehelper.error.GuestAuthenticationException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@Service
public class ClientIdentityService {

    private final GuestSessionService sessions;

    public ClientIdentityService(GuestSessionService sessions) {
        this.sessions = sessions;
    }

    public String conversationKey(
            UUID requestedUserId,
            String memoryId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Optional<GuestSessionService.TokenClaims> guest = currentGuestClaims(request);
        String owner;
        if (requestedUserId != null) {
            GuestSessionService.TokenClaims claims = guest
                    .filter(value -> value.userId().equals(requestedUserId))
                    .orElseThrow(() -> new GuestAuthenticationException(
                            "访客会话无效或与 userId 不匹配，请重新创建访客用户"));
            owner = "guest:" + claims.userId();
        } else if (guest.isPresent()) {
            owner = "guest:" + guest.get().userId();
        } else {
            owner = anonymousOwner(request, response);
        }
        return owner + ":memory:" + memoryId;
    }

    public boolean verifyGuest(UUID userId, HttpServletRequest request) {
        return currentGuestClaims(request)
                .map(claims -> claims.userId().equals(userId))
                .orElse(false);
    }

    public Optional<GuestSessionService.TokenClaims> currentGuestClaims(HttpServletRequest request) {
        Optional<GuestSessionService.TokenClaims> cookieClaims = cookieValue(
                request,
                GuestSessionService.GUEST_COOKIE
        ).flatMap(sessions::parse);
        if (cookieClaims.isPresent()) {
            return cookieClaims;
        }

        Optional<GuestSessionService.TokenClaims> bearerClaims = bearerToken(request).flatMap(sessions::parse);
        if (bearerClaims.isPresent()) {
            return bearerClaims;
        }
        return Optional.ofNullable(request.getHeader("X-Guest-Token"))
                .filter(StringUtils::hasText)
                .flatMap(sessions::parse);
    }

    public void addGuestCookie(HttpServletResponse response, String token) {
        addCookie(response, GuestSessionService.GUEST_COOKIE, token);
    }

    private String anonymousOwner(HttpServletRequest request, HttpServletResponse response) {
        Optional<GuestSessionService.TokenClaims> existing = cookieValue(request, GuestSessionService.ANONYMOUS_COOKIE)
                .flatMap(sessions::parse);
        if (existing.isPresent()) {
            return "anonymous:" + existing.get().userId();
        }

        String token = sessions.createAnonymousToken();
        GuestSessionService.TokenClaims claims = sessions.parse(token).orElseThrow();
        addCookie(response, GuestSessionService.ANONYMOUS_COOKIE, token);
        return "anonymous:" + claims.userId();
    }

    private Optional<String> bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.of(authorization.substring(7).trim());
        }
        return Optional.empty();
    }

    private Optional<String> cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private void addCookie(HttpServletResponse response, String name, String value) {
        Duration ttl = sessions.cookieSettings().getGuestTtl();
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(sessions.cookieSettings().isSecureCookies())
                .sameSite(sessions.cookieSettings().getSameSite())
                .path("/")
                .maxAge(ttl)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
