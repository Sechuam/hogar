package com.hogar.household.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de invitaciones ({@code hogar.invitations.*}).
 *
 * @param defaultTtl validez cuando la petición no indica {@code ttlHours}
 * @param maxTtl     validez máxima; cualquier TTL (pedido o por defecto) se acota a este valor
 */
@ConfigurationProperties("hogar.invitations")
public record InvitationProperties(Duration defaultTtl, Duration maxTtl) {

    public InvitationProperties {
        Objects.requireNonNull(defaultTtl, "hogar.invitations.default-ttl");
        Objects.requireNonNull(maxTtl, "hogar.invitations.max-ttl");
        if (!defaultTtl.isPositive() || !maxTtl.isPositive()) {
            throw new IllegalArgumentException("hogar.invitations TTLs must be positive");
        }
    }
}
