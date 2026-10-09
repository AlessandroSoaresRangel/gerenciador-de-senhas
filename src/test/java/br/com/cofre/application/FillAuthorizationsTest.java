package br.com.cofre.application;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class FillAuthorizationsTest {
    @Test
    void boundToAccountOriginDocumentAndSingleUse() {
        var permits = new FillAuthorizations(Clock.systemUTC());
        var ticket = permits.issue("https://exemplo.com", "account", "document");
        permits.consume(ticket.token(), "https://exemplo.com", "account", "document");
        assertThrows(IllegalStateException.class, () -> permits.consume(ticket.token(), "https://exemplo.com", "account", "document"));
        var wrong = permits.issue("https://exemplo.com", "account", "document");
        assertThrows(IllegalStateException.class, () -> permits.consume(wrong.token(), "https://falso.com", "account", "document"));
        var blocked = permits.issue("https://exemplo.com", "account", "document");
        permits.clear();
        assertThrows(IllegalStateException.class, () -> permits.consume(blocked.token(), "https://exemplo.com", "account", "document"));
    }

    @Test
    void rejectExpiredAuthorization() {
        class MutableClock extends Clock {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
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
                return now;
            }
        }
        var clock = new MutableClock();
        var permits = new FillAuthorizations(clock);
        var ticket = permits.issue("https://exemplo.com", "account", "document");
        clock.now = clock.now.plusSeconds(11);
        assertThrows(IllegalStateException.class, () -> permits.consume(ticket.token(), "https://exemplo.com", "account", "document"));
    }
}
