package com.hogar.shared.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de los tokens propios.
 *
 * @param secret          secreto HMAC en Base64 (mínimo 32 bytes decodificados). Solo por env.
 * @param issuer          valor del claim {@code iss}.
 * @param accessTokenTtl  vida del access token (corta).
 * @param refreshTokenTtl vida del refresh token.
 */
@Validated
@ConfigurationProperties("hogar.security.jwt")
public record JwtProperties(
        @NotBlank(message = "Define JWT_SECRET (openssl rand -base64 48)") String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl) {

    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret != null && !secret.isBlank()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(secret.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("JWT_SECRET debe estar en Base64", e);
            }
            if (decoded.length < MIN_SECRET_BYTES) {
                throw new IllegalArgumentException(
                        "JWT_SECRET debe tener al menos " + MIN_SECRET_BYTES + " bytes (tiene " + decoded.length + ")");
            }
        }
    }

    byte[] secretBytes() {
        return Base64.getDecoder().decode(secret.trim());
    }
}
