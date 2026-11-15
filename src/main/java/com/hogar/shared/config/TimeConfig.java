package com.hogar.shared.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Expone un {@link Clock} en UTC para que el código de negocio nunca llame a {@code now()}
 * directamente y los tests puedan fijar el tiempo.
 *
 * <p>El reloj se trunca a microsegundos, la precisión de {@code timestamptz} en PostgreSQL: en Linux
 * {@code Clock.systemUTC()} da nanosegundos y la respuesta de un alta no coincidiría con lo que
 * después se lee de la BD (D-067).
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

    @Bean
    Clock clock() {
        return new MicrosecondClock(Clock.systemUTC());
    }

    /**
     * No se usa {@code Clock.tick(base, 1 µs)}: su {@code millis()} divide entre
     * {@code tickNanos / 1_000_000}, que vale 0, y lanza {@link ArithmeticException}.
     */
    static final class MicrosecondClock extends Clock {

        private final Clock base;

        MicrosecondClock(Clock base) {
            this.base = base;
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MicrosecondClock(base.withZone(zone));
        }

        @Override
        public Instant instant() {
            return base.instant().truncatedTo(ChronoUnit.MICROS);
        }

        @Override
        public long millis() {
            return base.millis();
        }
    }
}
