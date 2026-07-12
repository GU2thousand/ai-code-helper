package com.aicodehelper.user;

import com.aicodehelper.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class GuestSessionService {

    public static final String GUEST_COOKIE = "AI_GUEST_TOKEN";
    public static final String ANONYMOUS_COOKIE = "AI_ANON_SESSION";

    private static final Logger log = LoggerFactory.getLogger(GuestSessionService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Clock clock;
    private final byte[] secret;
    private final AppProperties.Security security;

    public GuestSessionService(Clock clock, AppProperties properties) {
        this.clock = clock;
        this.security = properties.getSecurity();
        if (StringUtils.hasText(security.getTokenSecret())) {
            byte[] configuredSecret = security.getTokenSecret().getBytes(StandardCharsets.UTF_8);
            if (configuredSecret.length < 32) {
                throw new IllegalStateException(
                        "APP_AUTH_TOKEN_SECRET must contain at least 32 UTF-8 bytes when configured");
            }
            this.secret = configuredSecret;
        } else {
            this.secret = new byte[32];
            new SecureRandom().nextBytes(this.secret);
            log.warn("APP_AUTH_TOKEN_SECRET is absent; guest sessions will be invalid after restart");
        }
    }

    public GuestSession create(String requestedDisplayName) {
        UUID userId = UUID.randomUUID();
        return renew(userId, requestedDisplayName, null);
    }

    public GuestSession renew(UUID userId, String requestedDisplayName, String existingDisplayName) {
        Instant expiresAt = clock.instant().plus(security.getGuestTtl());
        String displayName;
        if (StringUtils.hasText(requestedDisplayName)) {
            displayName = requestedDisplayName.trim();
        } else if (StringUtils.hasText(existingDisplayName)) {
            displayName = existingDisplayName;
        } else {
            displayName = "访客-" + userId.toString().substring(0, 8);
        }
        return new GuestSession(userId, displayName, expiresAt, issueToken(userId, expiresAt, displayName));
    }

    public String createAnonymousToken() {
        return issueToken(UUID.randomUUID(), clock.instant().plus(security.getGuestTtl()), "");
    }

    public boolean verify(UUID expectedUserId, String token) {
        return parse(token)
                .filter(claims -> claims.userId().equals(expectedUserId))
                .isPresent();
    }

    public Optional<TokenClaims> parse(String token) {
        try {
            if (!StringUtils.hasText(token)) {
                return Optional.empty();
            }
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                return Optional.empty();
            }
            String signedValue = parts[0] + "." + parts[1];
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expectedSignature = hmac(signedValue);
            if (!MessageDigest.isEqual(suppliedSignature, expectedSignature)) {
                return Optional.empty();
            }

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String[] claims = payload.split(":", 3);
            if (claims.length < 2) {
                return Optional.empty();
            }
            UUID userId = UUID.fromString(claims[0]);
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(claims[1]));
            if (!expiresAt.isAfter(clock.instant())) {
                return Optional.empty();
            }
            String displayName = claims.length == 3 && !claims[2].isBlank()
                    ? new String(Base64.getUrlDecoder().decode(claims[2]), StandardCharsets.UTF_8)
                    : "";
            return Optional.of(new TokenClaims(userId, expiresAt, displayName));
        } catch (RuntimeException invalidToken) {
            return Optional.empty();
        }
    }

    public AppProperties.Security cookieSettings() {
        return security;
    }

    private String issueToken(UUID userId, Instant expiresAt, String displayName) {
        String encodedDisplayName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(displayName.getBytes(StandardCharsets.UTF_8));
        String payload = userId + ":" + expiresAt.getEpochSecond() + ":" + encodedDisplayName;
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signedValue = "v1." + encodedPayload;
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(signedValue));
        return signedValue + "." + signature;
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to sign a guest session", error);
        }
    }

    public record GuestSession(UUID userId, String displayName, Instant expiresAt, String token) {
    }

    public record TokenClaims(UUID userId, Instant expiresAt, String displayName) {
    }
}
