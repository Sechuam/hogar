package com.hogar.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.shared.security.JwtProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class AccessTokenIssuerTest {

    private static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1ub3QtZm9yLXByb2R1Y3Rpb24tMDEyMzQ1Njc4OWFiY2Q=";

    @Test
    void issuesHs256TokenWithExpectedClaims() {
        // Instante reciente: el decoder por defecto valida exp contra el reloj real.
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256");
        JwtProperties props = new JwtProperties(SECRET, "hogar-test", Duration.ofMinutes(15), Duration.ofDays(30));
        AccessTokenIssuer issuer = new AccessTokenIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(key)), props,
                Clock.fixed(now, ZoneOffset.UTC));
        UUID userId = UUID.randomUUID();

        AccessTokenIssuer.AccessToken token = issuer.issue(userId);

        Jwt jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().decode(token.value());
        assertThat(token.expiresIn()).isEqualTo(900);
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("hogar-test");
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
    }
}
