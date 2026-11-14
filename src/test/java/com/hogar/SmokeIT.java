package com.hogar;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers.TestUser;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Prueba de humo: la app arranca contra PostgreSQL, Flyway migra y la seguridad base responde. */
class SmokeIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void healthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void validAccessTokenAuthenticates() {
        TestUser user = users().create("Ana");

        ResponseEntity<String> response = rest.exchange("/api/__no-existe__", HttpMethod.GET,
                new HttpEntity<>(user.bearer()), String.class);

        // Autenticado: llega a Spring MVC, que responde 404 (no 401).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void expiredAccessTokenIsRejected() {
        TestUser user = users().create("Ana");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(users().expiredTokenFor(user.id()));

        ResponseEntity<String> response = rest.exchange("/api/__no-existe__", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"unauthorized\"");
    }

    @Test
    void tamperedAccessTokenIsRejected() {
        TestUser user = users().create("Ana");
        String token = user.accessToken();
        // Cambia un carácter del centro de la firma: el último de una firma HS256 en base64url
        // solo lleva 4 bits útiles y cambiarlo (B→A) puede no alterar los bytes decodificados.
        int signatureStart = token.lastIndexOf('.') + 1;
        int pos = signatureStart + (token.length() - signatureStart) / 2;
        String tampered = token.substring(0, pos) + (token.charAt(pos) == 'A' ? 'B' : 'A') + token.substring(pos + 1);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tampered);

        ResponseEntity<String> response = rest.exchange("/api/__no-existe__", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void flywayAppliedBaselineMigration() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version = '1'", Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void openApiDocsArePublic() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Hogar API");
    }

    @Test
    void protectedEndpointsReturn401AsProblemDetail() {
        ResponseEntity<String> response = rest.getForEntity("/api/households", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertThat(response.getBody()).contains("\"code\":\"unauthorized\"");
    }
}
