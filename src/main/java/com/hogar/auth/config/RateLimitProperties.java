package com.hogar.auth.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Rate limiting de los endpoints de autenticación.
 *
 * @param capacity     intentos permitidos por clave antes de devolver 429
 * @param refillPeriod tiempo en el que se recargan los {@code capacity} intentos (de forma gradual)
 */
@Validated
@ConfigurationProperties("hogar.security.rate-limit")
public record RateLimitProperties(@Positive int capacity, @NotNull Duration refillPeriod) {

    public RateLimitProperties {
        if (refillPeriod != null && (refillPeriod.isZero() || refillPeriod.isNegative())) {
            throw new IllegalArgumentException("hogar.security.rate-limit.refill-period debe ser positivo");
        }
    }
}
