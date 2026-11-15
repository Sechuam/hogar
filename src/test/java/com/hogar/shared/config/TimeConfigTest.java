package com.hogar.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeConfigTest {

    @Test
    void clockIsUtcAndNeverFinerThanPostgresMicroseconds() {
        Clock clock = new TimeConfig().clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        for (int i = 0; i < 1_000; i++) {
            Instant now = clock.instant();
            assertThat(now.getNano() % 1_000).as(now.toString()).isZero();
        }
        // Clock.tick(base, 1 µs).millis() lanza ArithmeticException: el rate limiter lo usa.
        assertThat(clock.millis()).isPositive();
    }

    @Test
    void truncatesANanosecondBaseClockAndKeepsTruncatingInOtherZones() {
        Instant nanos = Instant.parse("2026-11-14T23:48:39.144827229Z");
        Clock clock = new TimeConfig.MicrosecondClock(Clock.fixed(nanos, ZoneOffset.UTC));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-14T23:48:39.144827Z"));
        assertThat(clock.millis()).isEqualTo(nanos.toEpochMilli());

        Clock madrid = clock.withZone(ZoneId.of("Europe/Madrid"));
        assertThat(madrid.getZone()).isEqualTo(ZoneId.of("Europe/Madrid"));
        assertThat(madrid.instant()).isEqualTo(Instant.parse("2026-11-14T23:48:39.144827Z"));
    }
}
