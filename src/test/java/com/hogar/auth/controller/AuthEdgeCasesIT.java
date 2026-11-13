package com.hogar.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.hogar.auth.dto.AuthResponse;
import com.hogar.auth.dto.LoginRequest;
import com.hogar.auth.dto.RefreshTokenRequest;
import com.hogar.auth.dto.RegisterRequest;
import com.hogar.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Casos límite de autenticación: carreras, confusión de tokens, mayúsculas en email y cuerpos rotos. */
class AuthEdgeCasesIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "contraseña-segura-1";

    @RepeatedTest(3)
    void concurrentRefreshOfTheSameTokenNeverYieldsTwoSessions() throws Exception {
        AuthResponse session = register(uniqueEmail("carrera"));

        List<ResponseEntity<JsonNode>> responses = concurrently(List.of(
                () -> refresh(session.refreshToken()), () -> refresh(session.refreshToken())));

        // D-022: el segundo cuenta como reutilización y cae toda la familia, incluido el sucesor.
        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> winner = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK).findFirst().orElseThrow();
        ResponseEntity<JsonNode> successor = refresh(winner.getBody().get("refreshToken").asText());
        assertThat(successor.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(successor.getBody().get("code").asText()).isEqualTo("invalid-refresh-token");
    }

    @RepeatedTest(3)
    void concurrentRegistrationWithTheSameEmailIsConflictNotServerError() throws Exception {
        String email = uniqueEmail("doble");

        List<ResponseEntity<JsonNode>> responses = concurrently(List.of(
                () -> rest.postForEntity("/api/auth/register", new RegisterRequest(email, PASSWORD, "Uno"),
                        JsonNode.class),
                () -> rest.postForEntity("/api/auth/register",
                        new RegisterRequest(email.toUpperCase(Locale.ROOT), PASSWORD, "Dos"), JsonNode.class)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(r -> assertThat(r.getBody().get("code").asText()).isEqualTo("email-already-registered"));
    }

    @Test
    void emailIsCaseInsensitiveOnRegisterAndLogin() {
        String email = "Mixto." + UUID.randomUUID() + "@Test.Hogar";
        AuthResponse registered = register(email);
        assertThat(registered.user().email()).isEqualTo(email.toLowerCase(Locale.ROOT));

        ResponseEntity<AuthResponse> login = rest.postForEntity("/api/auth/login",
                new LoginRequest("  " + email.toUpperCase(Locale.ROOT) + " ", PASSWORD), AuthResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().user().id()).isEqualTo(registered.user().id());

        ResponseEntity<JsonNode> again = rest.postForEntity("/api/auth/register",
                new RegisterRequest(email.toLowerCase(Locale.ROOT), PASSWORD, "Otro"), JsonNode.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void accessAndRefreshTokensAreNotInterchangeable() {
        AuthResponse session = register(uniqueEmail("cruce"));

        ResponseEntity<JsonNode> accessAsRefresh = refresh(session.accessToken());
        assertThat(accessAsRefresh.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.BAD_REQUEST);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.refreshToken());
        ResponseEntity<JsonNode> refreshAsAccess = rest.exchange("/api/users/me", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(refreshAsAccess.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // El refresh token sigue siendo válido: los intentos anteriores no lo han consumido.
        assertThat(refresh(session.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void oversizedRefreshTokenIsValidationError() {
        ResponseEntity<JsonNode> response = refresh("x".repeat(257));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("validation-error");
    }

    @Test
    void logoutWithUnknownTokenIs204AndLogoutOfOneSessionKeepsOthers() {
        String email = uniqueEmail("logout");
        AuthResponse phone = register(email);
        AuthResponse laptop = login(email);

        assertThat(rest.postForEntity("/api/auth/logout", new RefreshTokenRequest("desconocido"), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.postForEntity("/api/auth/logout", new RefreshTokenRequest(phone.refreshToken()), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(refresh(phone.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(laptop.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"email\":[\"a@b.c\"],\"password\":\"12345678\",\"displayName\":\"X\"}",
            "{\"email\":{},\"password\":\"12345678\",\"displayName\":\"X\"}",
            "{\"email\":",
            "[]"})
    void registerWithMalformedBodyIsBadRequest(String body) {
        ResponseEntity<JsonNode> response = postRaw("/api/auth/register", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // JSON roto → bad-request; tipo incorrecto en "email" → validation-error de ese campo.
        assertThat(response.getBody().get("code").asText()).isIn("bad-request", "validation-error");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/google"})
    void authEndpointsWithMalformedBodyAreBadRequest(String path) {
        ResponseEntity<JsonNode> response = postRaw(path, "{\"x\":");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("bad-request");
    }

    @Test
    void passwordLongerThanBcryptLimitIsRejected() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/register",
                new RegisterRequest(uniqueEmail("larga"), "x".repeat(73), "Larga"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("validation-error");
    }

    // ---------------------------------------------------------------- utilidades

    private AuthResponse register(String email) {
        ResponseEntity<AuthResponse> response = rest.postForEntity("/api/auth/register",
                new RegisterRequest(email, PASSWORD, "Usuario"), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse login(String email) {
        ResponseEntity<AuthResponse> response = rest.postForEntity("/api/auth/login",
                new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> refresh(String refreshToken) {
        return rest.postForEntity("/api/auth/refresh", new RefreshTokenRequest(refreshToken), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postRaw(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@test.hogar";
    }

    private static List<ResponseEntity<JsonNode>> concurrently(List<Callable<ResponseEntity<JsonNode>>> calls)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<JsonNode>> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }
}
