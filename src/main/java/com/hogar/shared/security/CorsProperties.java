package com.hogar.shared.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Orígenes de navegador que pueden llamar a la API ({@code hogar.cors.allowed-origins}, D-069).
 * Vacío por defecto: sin cabeceras CORS, como exige un cliente solo nativo (D-066).
 *
 * @param allowedOrigins orígenes exactos ({@code esquema://host[:puerto]}); nunca comodines
 */
@ConfigurationProperties("hogar.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
        for (String origin : allowedOrigins) {
            if (origin.contains("*") || !origin.matches("https?://[^/\\s]+")) {
                throw new IllegalArgumentException(
                        "hogar.cors.allowed-origins admite solo orígenes exactos sin ruta ni comodines: " + origin);
            }
        }
    }

    public boolean enabled() {
        return !allowedOrigins.isEmpty();
    }
}
