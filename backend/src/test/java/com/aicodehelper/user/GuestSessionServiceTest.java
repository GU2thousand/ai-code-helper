package com.aicodehelper.user;

import com.aicodehelper.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuestSessionServiceTest {

    @Test
    void signsVerifiesAndExpiresGuestTokens() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setTokenSecret("unit-test-secret-with-enough-entropy");
        properties.getSecurity().setGuestTtl(Duration.ofMinutes(30));
        Instant now = Instant.parse("2026-07-11T12:00:00Z");
        GuestSessionService sessions = new GuestSessionService(
                Clock.fixed(now, ZoneOffset.UTC),
                properties
        );

        GuestSessionService.GuestSession session = sessions.create("Alice");
        assertThat(sessions.verify(session.userId(), session.token())).isTrue();
        assertThat(sessions.parse(session.token())).isPresent();

        GuestSessionService expiredVerifier = new GuestSessionService(
                Clock.fixed(now.plus(Duration.ofHours(1)), ZoneOffset.UTC),
                properties
        );
        assertThat(expiredVerifier.parse(session.token())).isEmpty();
    }

    @Test
    void renewalKeepsUserIdAndDisplayName() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setTokenSecret("unit-test-secret-with-enough-entropy");
        GuestSessionService sessions = new GuestSessionService(Clock.systemUTC(), properties);

        GuestSessionService.GuestSession created = sessions.create("Alice");
        GuestSessionService.TokenClaims claims = sessions.parse(created.token()).orElseThrow();
        GuestSessionService.GuestSession renewed = sessions.renew(
                created.userId(),
                null,
                claims.displayName()
        );

        assertThat(renewed.userId()).isEqualTo(created.userId());
        assertThat(renewed.displayName()).isEqualTo("Alice");
        assertThat(sessions.verify(created.userId(), renewed.token())).isTrue();
    }

    @Test
    void rejectsConfiguredSecretsShorterThanThirtyTwoBytes() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setTokenSecret("too-short");

        assertThatThrownBy(() -> new GuestSessionService(Clock.systemUTC(), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }
}
