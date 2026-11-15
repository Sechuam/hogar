package com.hogar.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers.TestUser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Con {@code hogar.cors.allowed-origins} configurado (D-069) solo esos orígenes reciben cabeceras CORS,
 * sin credenciales. El caso por defecto (sin CORS) está en {@link SecurityHardeningIT}.
 */
@TestPropertySource(properties = "hogar.cors.allowed-origins=http://localhost:5000, https://app.hogar.test")
class CorsEnabledIT extends AbstractIntegrationTest {

    private static final String ALLOWED = "http://localhost:5000";

    @Test
    void preflightFromAnAllowedOriginIsAcceptedWithTheHeadersTheClientNeeds() {
        ResponseEntity<String> response = preflight(ALLOWED, HttpMethod.POST, "authorization,content-type");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(headers.getAccessControlAllowMethods()).contains(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE);
        assertThat(headers.getAccessControlAllowHeaders()).map(String::toLowerCase)
                .contains("authorization", "content-type");
        assertThat(headers.getAccessControlAllowCredentials()).isFalse();
        assertThat(headers.getAccessControlMaxAge()).isEqualTo(3600);
    }

    @Test
    void actualRequestFromAnAllowedOriginExposesLocationAndRequestId() {
        TestUser ana = users().create("Ana");
        HttpHeaders headers = ana.bearer();
        headers.setOrigin(ALLOWED);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> created = rest.exchange("/api/households", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Piso"), headers), String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(created.getHeaders().getAccessControlExposeHeaders()).contains("Location", "X-Request-Id");
    }

    @Test
    void otherOriginsAreRejected() {
        ResponseEntity<String> response = preflight("https://evil.example", HttpMethod.POST, "authorization");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        // Un subdominio o un puerto distinto tampoco cuentan: la comparación es exacta.
        assertThat(preflight("http://localhost:5001", HttpMethod.GET, "authorization").getHeaders()
                .getAccessControlAllowOrigin()).isNull();
    }

    private ResponseEntity<String> preflight(String origin, HttpMethod method, String requestHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(method);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
        return rest.exchange("/api/households", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }
}
