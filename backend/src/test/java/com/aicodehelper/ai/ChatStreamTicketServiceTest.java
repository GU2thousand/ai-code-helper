package com.aicodehelper.ai;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.error.StreamTicketException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatStreamTicketServiceTest {

    @Test
    void ticketIsOwnerBoundAndConsumedOnlyOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-11T12:00:00Z"));
        AppProperties properties = new AppProperties();
        properties.getAi().setStreamTicketTtl(Duration.ofSeconds(30));
        ChatStreamTicketService service = new ChatStreamTicketService(clock, properties);

        ChatStreamTicketService.StreamTicket ticket = service.create("owner-a:memory:1", "1", "hello", true);

        assertThatThrownBy(() -> service.claim(ticket.id(), "owner-b:memory:1"))
                .isInstanceOf(StreamTicketException.class);
        assertThat(service.claim(ticket.id(), "owner-a:memory:1").regenerate()).isTrue();
        assertThatThrownBy(() -> service.claim(ticket.id(), "owner-a:memory:1"))
                .isInstanceOf(StreamTicketException.class);
    }

    @Test
    void scheduledCleanupRemovesExpiredPromptPayloads() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-11T12:00:00Z"));
        AppProperties properties = new AppProperties();
        properties.getAi().setStreamTicketTtl(Duration.ofSeconds(1));
        ChatStreamTicketService service = new ChatStreamTicketService(clock, properties);
        ChatStreamTicketService.StreamTicket ticket = service.create("owner:memory:1", "1", "hello", false);
        assertThat(service.pendingCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        service.removeExpiredOnSchedule();

        assertThat(service.pendingCount()).isZero();
        assertThatThrownBy(() -> service.describe(ticket.id())).isInstanceOf(StreamTicketException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
