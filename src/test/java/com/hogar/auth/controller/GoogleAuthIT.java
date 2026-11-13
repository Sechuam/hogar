package com.hogar.auth.controller;

import static com.hogar.auth.controller.AuthFlowIT.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.hogar.auth.dto.AuthResponse;
import com.hogar.auth.dto.GoogleLoginRequest;
import com.hogar.auth.dto.LoginRequest;
import com.hogar.auth.dto.RegisterRequest;
import com.hogar.auth.service.GoogleIdTokenVerifier;
import com.hogar.auth.service.GoogleIdTokenVerifier.GoogleIdentity;
import com.hogar.shared.error.UnauthorizedException;
import com.hogar.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Login con Google con el verificador simulado (la verificación real de firma/iss/aud/exp se
 * prueba en {@code NimbusGoogleIdTokenVerifierTest}).
 *
 * <p>Ojo: el rate limit de Google es solo por IP y aquí todas las peticiones salen de 127.0.0.1;
 * esta clase tiene su propio contexto (por el {@code @MockitoBean}) y no debe superar
 * {@code capacity} (5) llamadas a {@code /api/auth/google} en total.
 */
class GoogleAuthIT extends AbstractIntegrationTest {

    @MockitoBean
    private GoogleIdTokenVerifier googleVerifier;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void newGoogleUserIsCreatedWithoutPassword() {
        String email = uniqueEmail("google-nuevo");
        String subject = "sub-" + UUID.randomUUID();
        givenToken("token-nuevo", new GoogleIdentity(subject, email, true, "Gema"));

        ResponseEntity<AuthResponse> response = google("token-nuevo", AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse auth = response.getBody();
        assertThat(auth.user().email()).isEqualTo(email);
        assertThat(auth.user().displayName()).isEqualTo("Gema");
        assertThat(auth.user().hasPassword()).isFalse();
        assertThat(auth.user().linkedProviders()).containsExactly("GOOGLE");
        assertThat(jdbc.queryForObject("select email_verified from users where id = ?", Boolean.class,
                auth.user().id())).isTrue();
        // Un usuario solo-Google no puede entrar con contraseña: mismo 401 genérico.
        ResponseEntity<JsonNode> login = rest.postForEntity("/api/auth/login",
                new LoginRequest(email, "cualquier-cosa"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login.getBody().get("code").asText()).isEqualTo("invalid-credentials");
    }

    @Test
    void verifiedGoogleEmailLinksToExistingPasswordAccountAndSubjectWinsLater() {
        String email = uniqueEmail("google-vincula");
        AuthResponse registered = rest.postForEntity("/api/auth/register",
                new RegisterRequest(email, "contraseña-segura-1", "Pablo"), AuthResponse.class).getBody();
        String subject = "sub-" + UUID.randomUUID();
        givenToken("token-vincula", new GoogleIdentity(subject, email.toUpperCase(), true, "Pablo G"));

        AuthResponse linked = google("token-vincula", AuthResponse.class).getBody();

        assertThat(linked.user().id()).isEqualTo(registered.user().id());
        assertThat(linked.user().hasPassword()).isTrue();
        assertThat(linked.user().linkedProviders()).containsExactly("GOOGLE");

        // Más tarde el email de la cuenta de Google cambia: se entra por el subject vinculado.
        givenToken("token-email-nuevo", new GoogleIdentity(subject, uniqueEmail("cambiado"), true, "Pablo G"));
        AuthResponse again = google("token-email-nuevo", AuthResponse.class).getBody();
        assertThat(again.user().id()).isEqualTo(registered.user().id());
        assertThat(jdbc.queryForObject("select count(*) from user_identities where user_id = ?", Integer.class,
                registered.user().id())).isEqualTo(1);
    }

    @Test
    void unverifiedGoogleEmailIsRejectedAndNothingIsCreated() {
        String email = uniqueEmail("google-sin-verificar");
        givenToken("token-sin-verificar", new GoogleIdentity("sub-" + UUID.randomUUID(), email, false, "X"));

        ResponseEntity<JsonNode> response = google("token-sin-verificar", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("google-email-not-verified");
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
    }

    @Test
    void invalidGoogleTokenIs401() {
        when(googleVerifier.verify("token-falso"))
                .thenThrow(new UnauthorizedException("invalid-google-token", "El token de Google no es válido."));

        ResponseEntity<JsonNode> response = google("token-falso", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("invalid-google-token");
    }

    private void givenToken(String idToken, GoogleIdentity identity) {
        when(googleVerifier.verify(idToken)).thenReturn(identity);
    }

    private <T> ResponseEntity<T> google(String idToken, Class<T> type) {
        return rest.postForEntity("/api/auth/google", new GoogleLoginRequest(idToken), type);
    }
}
