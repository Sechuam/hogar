package com.hogar.auth.service;

import com.hogar.shared.error.ServiceUnavailableException;
import com.hogar.shared.error.UnauthorizedException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.stereotype.Component;

/**
 * Verificación real de ID tokens de Google. El {@link JwtDecoder} que recibe aporta la firma
 * (JWKS de Google en producción, una clave RSA local en los tests) y debe llevar los
 * validadores de {@link #validator}.
 */
@Component
public class NimbusGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(NimbusGoogleIdTokenVerifier.class);

    public static final String DECODER_QUALIFIER = "googleIdTokenDecoder";

    static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    static final String INVALID_GOOGLE_TOKEN = "invalid-google-token";

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final JwtDecoder decoder;

    public NimbusGoogleIdTokenVerifier(@Qualifier(DECODER_QUALIFIER) JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** {@code exp}/{@code nbf} (con el {@link Clock} dado), {@code iss} de Google y {@code aud} ∩ clientIds ≠ ∅. */
    public static OAuth2TokenValidator<Jwt> validator(Collection<String> clientIds, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        Set<String> allowedAudiences = Set.copyOf(clientIds);
        // getClaimAsString y no getIssuer(): "accounts.google.com" (sin esquema) no es una URL válida.
        // Set.of no admite contains(null): se comprueba antes.
        OAuth2TokenValidator<Jwt> issuer = jwt -> check(
                jwt.getClaimAsString("iss") != null && GOOGLE_ISSUERS.contains(jwt.getClaimAsString("iss")),
                "iss no es de Google");
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> aud = jwt.getAudience();
            return check(aud != null && aud.stream().anyMatch(allowedAudiences::contains),
                    "aud no corresponde a esta aplicación");
        };
        return new DelegatingOAuth2TokenValidator<>(timestamps, issuer, audience);
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (BadJwtException e) {
            // Firma, formato, iss, aud o exp no válidos. El token nunca se registra.
            throw invalidToken();
        } catch (JwtException e) {
            // No se pudo comprobar (p. ej. el JWKS de Google no responde): no es culpa del cliente.
            log.warn("No se pudo verificar el ID token de Google: {}", e.getMessage());
            throw new ServiceUnavailableException("google-unavailable",
                    "No se puede verificar el inicio de sesión con Google en este momento.");
        }
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (isBlank(subject) || isBlank(email)) {
            throw invalidToken();
        }
        return new GoogleIdentity(subject, email, isTrue(jwt.getClaim("email_verified")), jwt.getClaimAsString("name"));
    }

    /** Google envía {@code email_verified} como booleano, pero históricamente también como texto. */
    private static boolean isTrue(Object claim) {
        return claim instanceof Boolean b ? b : claim instanceof String s && Boolean.parseBoolean(s);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static OAuth2TokenValidatorResult check(boolean ok, String description) {
        return ok ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }

    private static UnauthorizedException invalidToken() {
        return new UnauthorizedException(INVALID_GOOGLE_TOKEN, "El token de Google no es válido.");
    }
}
