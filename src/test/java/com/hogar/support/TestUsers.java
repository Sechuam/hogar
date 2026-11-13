package com.hogar.support;

import com.hogar.shared.security.JwtProperties;
import com.hogar.user.domain.User;
import com.hogar.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Atajo para los IT que no prueban la autenticación en sí: crea un usuario directamente en BD
 * y le emite un access token firmado con la misma clave que la app. Así los tests de otras
 * features no dependen de los endpoints de login.
 */
public final class TestUsers {

    /** Contraseña en claro de los usuarios creados con {@link #create(String)}. */
    public static final String PASSWORD = "password-de-test-123";

    private final UserRepository users;
    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final Clock clock;
    private final String passwordHash;

    public TestUsers(UserRepository users, JwtEncoder encoder, JwtProperties props, Clock clock, String passwordHash) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
        this.passwordHash = passwordHash;
    }

    public record TestUser(UUID id, String email, String displayName, String accessToken) {

        public HttpHeaders bearer() {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            return headers;
        }
    }

    /** Usuario con email único y contraseña {@link #PASSWORD}. */
    public TestUser create(String displayName) {
        String email = displayName.toLowerCase().replaceAll("[^a-z0-9]", "") + "-" + UUID.randomUUID() + "@test.hogar";
        User user = users.save(User.registerWithPassword(email, displayName, passwordHash, Instant.now(clock)));
        return new TestUser(user.getId(), user.getEmail(), displayName, tokenFor(user.getId()));
    }

    public String tokenFor(UUID userId) {
        return token(userId, Instant.now(clock), props.accessTokenTtl());
    }

    /** Token ya caducado (emitido hace 2 TTL), para probar el rechazo. */
    public String expiredTokenFor(UUID userId) {
        Duration ttl = props.accessTokenTtl();
        return token(userId, Instant.now(clock).minus(ttl.multipliedBy(2)), ttl);
    }

    private String token(UUID userId, Instant issuedAt, Duration ttl) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
