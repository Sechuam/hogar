package com.hogar.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.auth.service.GoogleIdTokenVerifier.GoogleIdentity;
import com.hogar.shared.error.UnauthorizedException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Implementación real con un decoder sobre una clave RSA local en lugar del JWKS de Google. */
class NimbusGoogleIdTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");
    private static final String CLIENT_ID = "app.apps.googleusercontent.com";

    private static KeyPair googleKeys;
    private static KeyPair otherKeys;

    private final NimbusGoogleIdTokenVerifier verifier = verifierTrusting((RSAPublicKey) googleKeys.getPublic());

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        googleKeys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
    }

    @Test
    void validTokenYieldsIdentity() {
        GoogleIdentity identity = verifier.verify(token(googleKeys, c -> { }));

        assertThat(identity).isEqualTo(new GoogleIdentity("google-sub-1", "ana@gmail.com", true, "Ana"));
    }

    @Test
    void acceptsIssuerWithoutScheme() {
        assertThat(verifier.verify(token(googleKeys, c -> c.issuer("accounts.google.com"))).subject())
                .isEqualTo("google-sub-1");
    }

    @Test
    void acceptsAudienceListContainingOurClientId() {
        assertThat(verifier.verify(token(googleKeys, c -> c.audience(List.of("otra-app", CLIENT_ID)))).email())
                .isEqualTo("ana@gmail.com");
    }

    @Test
    void emailVerifiedAsStringIsUnderstood() {
        assertThat(verifier.verify(token(googleKeys, c -> c.claim("email_verified", "false"))).emailVerified()).isFalse();
        assertThat(verifier.verify(token(googleKeys, c -> c.claim("email_verified", "true"))).emailVerified()).isTrue();
    }

    @Test
    void rejectsForeignIssuer() {
        assertInvalid(token(googleKeys, c -> c.issuer("https://evil.example.com")));
    }

    @Test
    void rejectsMissingIssuer() {
        assertInvalid(token(googleKeys, c -> c.claims(claims -> claims.remove("iss"))));
    }

    @Test
    void rejectsForeignAudience() {
        assertInvalid(token(googleKeys, c -> c.audience(List.of("otra-app.apps.googleusercontent.com"))));
    }

    @Test
    void rejectsExpiredToken() {
        assertInvalid(token(googleKeys, c -> c
                .issuedAt(NOW.minus(Duration.ofHours(2)))
                .expiresAt(NOW.minus(Duration.ofHours(1)))));
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        assertInvalid(token(otherKeys, c -> { }));
    }

    @Test
    void rejectsGarbage() {
        assertInvalid("no-es-un-jwt");
    }

    @Test
    void rejectsTokenWithoutEmail() {
        assertInvalid(token(googleKeys, c -> c.claims(claims -> claims.remove("email"))));
    }

    @Test
    void rejectsEverythingWhenNoClientIdIsConfigured() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) googleKeys.getPublic()).build();
        decoder.setJwtValidator(NimbusGoogleIdTokenVerifier.validator(List.of(), Clock.fixed(NOW, ZoneOffset.UTC)));
        NimbusGoogleIdTokenVerifier unconfigured = new NimbusGoogleIdTokenVerifier(decoder);

        assertThatThrownBy(() -> unconfigured.verify(token(googleKeys, c -> { })))
                .isInstanceOf(UnauthorizedException.class);
    }

    private void assertInvalid(String idToken) {
        assertThatThrownBy(() -> verifier.verify(idToken))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("invalid-google-token");
    }

    private static NimbusGoogleIdTokenVerifier verifierTrusting(RSAPublicKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(NimbusGoogleIdTokenVerifier.validator(List.of(CLIENT_ID), Clock.fixed(NOW, ZoneOffset.UTC)));
        return new NimbusGoogleIdTokenVerifier(decoder);
    }

    private static String token(KeyPair keys, Consumer<JwtClaimsSet.Builder> customizer) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                .privateKey((RSAPrivateKey) keys.getPrivate())
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("https://accounts.google.com")
                .audience(List.of(CLIENT_ID))
                .subject("google-sub-1")
                .issuedAt(NOW.minusSeconds(60))
                .expiresAt(NOW.plus(Duration.ofMinutes(59)))
                .claim("email", "ana@gmail.com")
                .claim("email_verified", true)
                .claim("name", "Ana");
        customizer.accept(claims);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
                .getTokenValue();
    }
}
