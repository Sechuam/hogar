package com.hogar.auth.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Rate limiting de los endpoints de autenticación.
 *
 * @param capacity     intentos permitidos por clave (IP + email) antes de devolver 429
 * @param refillPeriod tiempo en el que se recargan los intentos (de forma gradual)
 * @param ipCapacity   intentos permitidos por IP sumando todos los emails: frena la enumeración
 *                     de emails en el registro y el <i>password spraying</i> en el login
 */
@Validated
@ConfigurationProperties("hogar.security.rate-limit")
public record RateLimitProperties(@Positive int capacity, @NotNull Duration refillPeriod, @Positive int ipCapacity) {

    public RateLimitProperties {
        if (refillPeriod != null && (refillPeriod.isZero() || refillPeriod.isNegative())) {
            throw new IllegalArgumentException("hogar.security.rate-limit.refill-period debe ser positivo");
        }
    }
}
