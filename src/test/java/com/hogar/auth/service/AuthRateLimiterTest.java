package com.hogar.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import com.hogar.auth.config.RateLimitProperties;
import com.hogar.shared.error.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T09:00:00Z"));
    private AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        Ticker ticker = () -> clock.millis() * 1_000_000L;
        limiter = new AuthRateLimiter(new RateLimitProperties(5, Duration.ofMinutes(1), 8), clock, ticker);
    }

    @Test
    void sixthLoginAttemptForSameIpAndEmailIsRejectedWithRetryAfter() {
        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("10.0.0.1", "ana@example.com");
        }

        assertThatThrownBy(() -> limiter.checkLogin("10.0.0.1", " ANA@example.com "))
                .isInstanceOfSatisfying(TooManyRequestsException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("rate-limited");
                    // Recarga gradual: 5 intentos/min → 1 cada 12 s.
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(12);
                });
    }

    @Test
    void keysAreIndependentPerIpEmailAndEndpoint() {
        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("10.0.0.1", "ana@example.com");
        }

        assertThatCode(() -> {
            limiter.checkLogin("10.0.0.2", "ana@example.com");
            limiter.checkLogin("10.0.0.1", "otra@example.com");
            limiter.checkRegister("10.0.0.1", "ana@example.com");
        }).doesNotThrowAnyException();
    }

    @Test
    void rotatingEmailsFromOneIpHitsThePerIpLimit() {
        // ipCapacity = 8: con emails distintos no se agota el cupo IP+email, pero sí el de IP.
        for (int i = 0; i < 8; i++) {
            limiter.checkLogin("10.0.0.9", "victima" + i + "@example.com");
        }

        assertThatThrownBy(() -> limiter.checkLogin("10.0.0.9", "otra@example.com"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.checkLogin("10.0.0.10", "otra@example.com")).doesNotThrowAnyException();
        // El registro tiene su propio cupo por IP.
        assertThatCode(() -> limiter.checkRegister("10.0.0.9", "nueva@example.com")).doesNotThrowAnyException();
    }

    @Test
    void googleIsLimitedByIpOnly() {
        for (int i = 0; i < 5; i++) {
            limiter.checkGoogle("10.0.0.1");
        }

        assertThatThrownBy(() -> limiter.checkGoogle("10.0.0.1")).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.checkGoogle("10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    void attemptsAreRefilledOverTime() {
        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("10.0.0.1", "ana@example.com");
        }
        clock.advance(Duration.ofSeconds(12));

        assertThatCode(() -> limiter.checkLogin("10.0.0.1", "ana@example.com")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkLogin("10.0.0.1", "ana@example.com"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void idleBucketsAreEvictedOnceTheyWouldBeFullAgain() {
        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("10.0.0.1", "ana@example.com");
        }
        clock.advance(Duration.ofMinutes(2));

        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("10.0.0.1", "ana@example.com");
        }
        assertThatThrownBy(() -> limiter.checkLogin("10.0.0.1", "ana@example.com"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
