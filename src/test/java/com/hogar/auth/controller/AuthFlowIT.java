package com.hogar.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.hogar.auth.dto.AuthResponse;
import com.hogar.auth.dto.LoginRequest;
import com.hogar.auth.dto.RefreshTokenRequest;
import com.hogar.auth.dto.RegisterRequest;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers;
import com.hogar.support.TestUsers.TestUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Flujos HTTP completos de registro, login, refresh, logout y perfil contra PostgreSQL real. */
class AuthFlowIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "contraseña-segura-1";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registerReturns201WithTokensAndMeReturnsTheProfile() {
        String email = uniqueEmail("registro");

        ResponseEntity<AuthResponse> registered = rest.postForEntity("/api/auth/register",
                new RegisterRequest(email.toUpperCase(), PASSWORD, "Ana"), AuthResponse.class);

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getHeaders().getLocation()).hasToString("/api/users/me");
        AuthResponse auth = registered.getBody();
        assertThat(auth).isNotNull();
        assertThat(auth.tokenType()).isEqualTo("Bearer");
        assertThat(auth.expiresIn()).isEqualTo(900);
        assertThat(auth.refreshToken()).hasSize(43);
        assertThat(auth.user().email()).isEqualTo(email);
        assertThat(auth.user().hasPassword()).isTrue();

        ResponseEntity<JsonNode> me = me(auth.accessToken());
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("id").asText()).isEqualTo(auth.user().id().toString());
        assertThat(me.getBody().get("email").asText()).isEqualTo(email);
        assertThat(me.getBody().get("linkedProviders")).isEmpty();
    }

    @Test
    void passwordIsStoredAsBcryptAndRefreshTokenOnlyAsSha256() {
        AuthResponse auth = register(uniqueEmail("hash"));

        String passwordHash = jdbc.queryForObject("select password_hash from users where id = ?",
                String.class, auth.user().id());
        assertThat(passwordHash).startsWith("$2").doesNotContain(PASSWORD);
        String storedHash = jdbc.queryForObject("select token_hash from refresh_tokens where user_id = ?",
                String.class, auth.user().id());
        assertThat(storedHash).hasSize(64).matches("[0-9a-f]+").isNotEqualTo(auth.refreshToken());
    }

    @Test
    void registerWithExistingEmailIsConflict() {
        String email = uniqueEmail("duplicado");
        register(email);

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/register",
                new RegisterRequest(email.toUpperCase(), PASSWORD, "Otra"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("email-already-registered");
    }

    @Test
    void registerValidationErrorsListEachField() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/register",
                Map.of("email", "no-es-email", "password", "corta", "displayName", ""), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("validation-error");
        assertThat(response.getBody().get("errors").findValuesAsText("field"))
                .containsExactlyInAnyOrder("email", "password", "displayName");
    }

    @Test
    void loginValidationErrorIs400() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/login", Map.of("email", ""), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").findValuesAsText("field")).contains("email", "password");
    }

    @Test
    void loginWithRightPasswordReturnsTokens() {
        String email = uniqueEmail("login");
        register(email);

        ResponseEntity<AuthResponse> response = rest.postForEntity("/api/auth/login",
                new LoginRequest(email, PASSWORD), AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me(response.getBody().accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void loginFailuresAreIndistinguishable() {
        String email = uniqueEmail("login-ko");
        register(email);

        ResponseEntity<JsonNode> wrongPassword = rest.postForEntity("/api/auth/login",
                new LoginRequest(email, "otra-contraseña"), JsonNode.class);
        ResponseEntity<JsonNode> unknownEmail = rest.postForEntity("/api/auth/login",
                new LoginRequest(uniqueEmail("nadie"), PASSWORD), JsonNode.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("code").asText()).isEqualTo("invalid-credentials");
        assertThat(unknownEmail.getBody().get("code")).isEqualTo(wrongPassword.getBody().get("code"));
        assertThat(unknownEmail.getBody().get("detail")).isEqualTo(wrongPassword.getBody().get("detail"));
    }

    @Test
    void sixthLoginAttemptFromSameIpAndEmailIsRateLimited() {
        String email = uniqueEmail("fuerza-bruta");
        register(email);
        for (int i = 0; i < 5; i++) {
            ResponseEntity<JsonNode> attempt = rest.postForEntity("/api/auth/login",
                    new LoginRequest(email, "intento-" + i), JsonNode.class);
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<JsonNode> blocked = rest.postForEntity("/api/auth/login",
                new LoginRequest(email.toUpperCase(), PASSWORD), JsonNode.class);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getBody().get("code").asText()).isEqualTo("rate-limited");
        assertThat(Long.parseLong(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        // Otro email desde la misma IP no se ve afectado.
        String other = uniqueEmail("otra");
        register(other);
        assertThat(rest.postForEntity("/api/auth/login", new LoginRequest(other, PASSWORD), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void refreshRotatesAndReusingTheOldTokenRevokesTheNewOneToo() {
        AuthResponse login = register(uniqueEmail("refresh"));

        ResponseEntity<AuthResponse> rotated = refresh(login.refreshToken(), AuthResponse.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse second = rotated.getBody();
        assertThat(second.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(second.user().id()).isEqualTo(login.user().id());
        assertThat(me(second.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        // El token viejo ya no vale y presentarlo se trata como robo: cae toda la familia.
        ResponseEntity<JsonNode> reused = refresh(login.refreshToken(), JsonNode.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getBody().get("code").asText()).isEqualTo("invalid-refresh-token");

        ResponseEntity<JsonNode> successor = refresh(second.refreshToken(), JsonNode.class);
        assertThat(successor.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(successor.getBody().get("code").asText()).isEqualTo("invalid-refresh-token");
    }

    @Test
    void refreshDoesNotAffectOtherSessionsOfTheSameUser() {
        String email = uniqueEmail("sesiones");
        AuthResponse phone = register(email);
        AuthResponse laptop = rest.postForEntity("/api/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class)
                .getBody();

        rest.postForEntity("/api/auth/logout", new RefreshTokenRequest(phone.refreshToken()), Void.class);

        assertThat(refresh(phone.refreshToken(), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(laptop.refreshToken(), AuthResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownRefreshTokenIsRejected() {
        ResponseEntity<JsonNode> response = refresh("token-que-no-existe", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("invalid-refresh-token");
    }

    @Test
    void logoutRevokesTheSessionAndIsIdempotent() {
        AuthResponse auth = register(uniqueEmail("logout"));
        AuthResponse rotated = refresh(auth.refreshToken(), AuthResponse.class).getBody();

        ResponseEntity<Void> first = rest.postForEntity("/api/auth/logout",
                new RefreshTokenRequest(rotated.refreshToken()), Void.class);
        ResponseEntity<Void> again = rest.postForEntity("/api/auth/logout",
                new RefreshTokenRequest(rotated.refreshToken()), Void.class);
        ResponseEntity<Void> unknown = rest.postForEntity("/api/auth/logout",
                new RefreshTokenRequest("desconocido"), Void.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(rotated.refreshToken(), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Integer active = jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, auth.user().id());
        assertThat(active).isZero();
    }

    @Test
    void meRequiresAuthentication() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/users/me", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("unauthorized");
    }

    @Test
    void meWithExpiredTokenIs401() {
        TestUser user = users().create("Caducado");

        assertThat(me(users().expiredTokenFor(user.id())).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meForTestUserWorksWithHelperTokens() {
        TestUser user = users().create("Helper");

        ResponseEntity<JsonNode> me = me(user.accessToken());

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("displayName").asText()).isEqualTo("Helper");
        assertThat(rest.postForEntity("/api/auth/login", new LoginRequest(user.email(), TestUsers.PASSWORD),
                AuthResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private AuthResponse register(String email) {
        ResponseEntity<AuthResponse> response = rest.postForEntity("/api/auth/register",
                new RegisterRequest(email, PASSWORD, "Usuario"), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private <T> ResponseEntity<T> refresh(String refreshToken, Class<T> type) {
        return rest.postForEntity("/api/auth/refresh", new RefreshTokenRequest(refreshToken), type);
    }

    private ResponseEntity<JsonNode> me(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return rest.exchange("/api/users/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@test.hogar";
    }
}
