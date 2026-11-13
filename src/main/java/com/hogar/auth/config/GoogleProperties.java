package com.hogar.auth.config;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validación de ID tokens de Google.
 *
 * @param clientIds client IDs de la app aceptados como {@code aud}. Vacío → se rechaza todo
 *                  ID token (Google deshabilitado), sin impedir que la app arranque.
 * @param jwkSetUri JWKS público de Google con las claves de firma.
 */
@Validated
@ConfigurationProperties("hogar.security.google")
public record GoogleProperties(List<String> clientIds, @NotBlank String jwkSetUri) {

    public GoogleProperties {
        clientIds = clientIds == null ? List.of()
                : clientIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).toList();
    }
}
