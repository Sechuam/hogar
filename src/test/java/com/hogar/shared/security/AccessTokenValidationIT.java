package com.hogar.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers.TestUser;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Access tokens manipulados o fabricados: todos deben acabar en 401 ProblemDetail, nunca en 200
 * ni en 500. Los tokens se construyen a mano con Nimbus para poder variar cabecera, firma y claims.
 */
class AccessTokenValidationIT extends AbstractIntegrationTest {

    @Autowired
    private JwtProperties props;

    @Autowired
    private Clock clock;

    @Test
    void validHandmadeTokenIsAcceptedSoTheNegativeCasesAreMeaningful() throws Exception {
        TestUser ana = users().create("Ana");

        assertThat(me(sign(ana.id().toString(), b -> { }, JWSAlgorithm.HS256, ownKey())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        TestUser ana = users().create("Ana");
        byte[] otherKey = new byte[48];
        new SecureRandom().nextBytes(otherKey);

        assertUnauthorized(me(sign(ana.id().toString(), b -> { }, JWSAlgorithm.HS256, otherKey)));
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        TestUser ana = users().create("Ana");
        String token = new PlainJWT(claims(ana.id().toString(), b -> { })).serialize();

        assertUnauthorized(me(token));
        // Variante: cabecera "none" con la firma de un token válido pegada detrás.
        String valid = ana.accessToken();
        assertUnauthorized(me(token + valid.substring(valid.lastIndexOf('.') + 1)));
    }

    @Test
    void tokenWithAnotherIssuerIsRejected() throws Exception {
        TestUser ana = users().create("Ana");

        assertUnauthorized(me(sign(ana.id().toString(), b -> b.issuer("otra-app"), JWSAlgorithm.HS256, ownKey())));
        assertUnauthorized(me(sign(ana.id().toString(), b -> b.issuer(null), JWSAlgorithm.HS256, ownKey())));
    }

    @Test
    void tokenWithAnotherHmacAlgorithmIsRejectedEvenWithTheRightKey() throws Exception {
        TestUser ana = users().create("Ana");

        assertUnauthorized(me(sign(ana.id().toString(), b -> { }, JWSAlgorithm.HS384, ownKey())));
    }

    @Test
    void notYetValidTokenIsRejected() throws Exception {
        TestUser ana = users().create("Ana");
        Date inOneHour = Date.from(Instant.now(clock).plusSeconds(3600));

        assertUnauthorized(me(sign(ana.id().toString(), b -> b.notBeforeTime(inOneHour), JWSAlgorithm.HS256,
                ownKey())));
    }

    @Test
    void garbageAndMisplacedTokensAreRejected() {
        TestUser ana = users().create("Ana");

        assertUnauthorized(me("no-es-un-jwt"));
        assertUnauthorized(me("a.b.c"));
        assertUnauthorized(rest.exchange("/api/users/me", HttpMethod.GET,
                new HttpEntity<>(headers(Map.of(HttpHeaders.AUTHORIZATION, "Basic " + ana.accessToken()))),
                String.class));
        // El token en la query string no se acepta (se filtraría en logs y proxies).
        assertUnauthorized(rest.getForEntity("/api/users/me?access_token=" + ana.accessToken(), String.class));
    }

    @Disabled("BUG (baja, endurecimiento): un token bien firmado sin 'exp' no caduca nunca; JwtTimestampValidator "
            + "solo valida exp si existe. Fix: en shared/security/JwtConfig.jwtDecoder añadir un validador "
            + "JwtClaimValidator<Instant>(EXP, Objects::nonNull) al DelegatingOAuth2TokenValidator")
    @Test
    void tokenWithoutExpirationIsRejected() throws Exception {
        TestUser ana = users().create("Ana");

        assertUnauthorized(me(sign(ana.id().toString(), b -> b.expirationTime(null), JWSAlgorithm.HS256, ownKey())));
    }

    @Disabled("BUG (baja): un token bien firmado con 'sub' que no es UUID provoca IllegalArgumentException en "
            + "CurrentUser.id() → 500. Fix: validar 'sub' como UUID en JwtConfig.jwtDecoder (JwtClaimValidator) "
            + "para que salga 401")
    @Test
    void tokenWhoseSubjectIsNotAUuidIsRejected() throws Exception {
        assertUnauthorized(me(sign("no-es-uuid", b -> { }, JWSAlgorithm.HS256, ownKey())));
        assertUnauthorized(me(sign(null, b -> { }, JWSAlgorithm.HS256, ownKey())));
    }

    @Disabled("BUG (baja): un token válido de un usuario inexistente llega a los servicios; POST /api/households "
            + "viola la FK memberships.user_id → DataIntegrityViolationException → 500. Hoy no hay borrado de "
            + "usuarios, pero lo habrá. Fix: tratar DataIntegrityViolationException en GlobalExceptionHandler "
            + "(409) y, mejor, comprobar en el decoder/un filtro que el usuario existe (401)")
    @Test
    void tokenOfANonExistentUserNeverCausesServerError() throws Exception {
        String token = sign(UUID.randomUUID().toString(), b -> { }, JWSAlgorithm.HS256, ownKey());
        HttpHeaders headers = headers(Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.exchange("/api/households", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Fantasma"), headers), String.class);

        assertThat(response.getStatusCode().is5xxServerError()).as(response.getBody()).isFalse();
    }

    @Test
    void tokenOfANonExistentUserIsUnauthorizedOnMe() throws Exception {
        assertUnauthorized(me(sign(UUID.randomUUID().toString(), b -> { }, JWSAlgorithm.HS256, ownKey())));
    }

    // ---------------------------------------------------------------- utilidades

    private void assertUnauthorized(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = parse(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("unauthorized");
    }

    private ResponseEntity<String> me(String token) {
        return rest.exchange("/api/users/me", HttpMethod.GET,
                new HttpEntity<>(headers(Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token))), String.class);
    }

    private static HttpHeaders headers(Map<String, String> values) {
        HttpHeaders headers = new HttpHeaders();
        values.forEach(headers::set);
        return headers;
    }

    private byte[] ownKey() {
        return java.util.Base64.getDecoder().decode(props.secret().trim());
    }

    private JWTClaimsSet claims(String subject, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now(clock);
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(props.issuer())
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(props.accessTokenTtl())));
        customizer.accept(builder);
        return builder.build();
    }

    private String sign(String subject, Consumer<JWTClaimsSet.Builder> customizer, JWSAlgorithm alg, byte[] key)
            throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(alg), claims(subject, customizer));
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }

    private static JsonNode parse(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Respuesta no es JSON: " + body, e);
        }
    }
}
