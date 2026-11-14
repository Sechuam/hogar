package com.hogar.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers;
import com.hogar.support.TestUsers.TestUser;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Endurecimiento transversal: rate limiting no eludible con cabeceras de proxy, cabeceras de seguridad,
 * CORS cerrado, errores sin detalles internos y documentación pública sin nada sensible.
 */
class SecurityHardeningIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    /**
     * Sin {@code server.forward-headers-strategy} la IP es la de la conexión: rotar
     * {@code X-Forwarded-For}/{@code Forwarded}/{@code X-Real-IP} no da cupos nuevos.
     */
    @Test
    void spoofedProxyHeadersDoNotEscapeTheLoginRateLimit() {
        TestUser victim = users().create("Victima");
        List<String> spoofs = List.of("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5", "6.6.6.6");
        int attempt = 0;
        for (String ip : spoofs.subList(0, 5)) {
            ResponseEntity<String> response = login(victim.email(), "mala-" + attempt++, ip);
            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> blocked = login(victim.email(), TestUsers.PASSWORD, spoofs.get(5));

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    @Test
    void apiResponsesCarryDefensiveHeadersAlsoOnErrors() {
        TestUser user = users().create("Ana");
        ResponseEntity<String> ok = rest.exchange("/api/users/me", HttpMethod.GET,
                new HttpEntity<>(user.bearer()), String.class);
        ResponseEntity<String> unauthorized = rest.getForEntity("/api/users/me", String.class);

        for (ResponseEntity<String> response : List.of(ok, unauthorized)) {
            HttpHeaders headers = response.getHeaders();
            assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
            assertThat(headers.getCacheControl()).contains("no-store");
            assertThat(headers.get(HttpHeaders.SET_COOKIE)).as("API sin sesión ni cookies").isNull();
        }
    }

    /** Sin configuración CORS: un navegador en otro origen no puede leer la API (los clientes nativos no lo necesitan). */
    @Test
    void crossOriginBrowsersAreNotAllowed() {
        HttpHeaders preflight = new HttpHeaders();
        preflight.setOrigin("https://evil.example");
        preflight.setAccessControlRequestMethod(HttpMethod.POST);
        ResponseEntity<String> response = rest.exchange("/api/auth/login", HttpMethod.OPTIONS,
                new HttpEntity<>(preflight), String.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();

        HttpHeaders simple = new HttpHeaders();
        simple.setOrigin("https://evil.example");
        simple.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> login = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "", "password", ""), simple), String.class);
        assertThat(login.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    /**
     * Rutas rechazadas por el firewall de Spring Security, JSON roto, tipos de contenido o métodos no
     * admitidos, IDs con inyección y {@code /error} directo: ninguna respuesta lleva trazas, clases ni
     * mensajes internos.
     */
    @Test
    void errorResponsesNeverLeakInternals() {
        TestUser user = users().create("Ana");
        HttpHeaders json = user.bearer();
        json.setContentType(MediaType.APPLICATION_JSON);
        HttpHeaders xml = user.bearer();
        xml.setContentType(MediaType.APPLICATION_XML);
        List<ResponseEntity<String>> responses = List.of(
                // URI literal: la plantilla de RestTemplate normalizaría "//" antes de enviarla.
                rest.exchange(raw("/api//households"), HttpMethod.GET, new HttpEntity<>(user.bearer()), String.class),
                rest.exchange(raw("/api/households;x=1"), HttpMethod.GET, new HttpEntity<>(user.bearer()),
                        String.class),
                rest.exchange(raw("/api/households/%2e%2e/x"), HttpMethod.GET, new HttpEntity<>(user.bearer()),
                        String.class),
                rest.exchange("/api/households", HttpMethod.POST, new HttpEntity<>("{\"name\": [", json), String.class),
                rest.exchange("/api/households", HttpMethod.POST, new HttpEntity<>("<a/>", xml), String.class),
                rest.exchange("/api/households", HttpMethod.DELETE, new HttpEntity<>(user.bearer()), String.class),
                rest.exchange("/api/households/1%27%20OR%201=1", HttpMethod.GET, new HttpEntity<>(user.bearer()),
                        String.class));

        for (ResponseEntity<String> response : responses) {
            assertThat(response.getStatusCode().is4xxClientError()).as(response.getBody()).isTrue();
            assertNoInternals(response);
        }
        // /error pedido a mano: Boot responde 500 con "status":999 (sin error real que describir); feo pero
        // inocuo, no registra nada ni filtra detalles.
        assertNoInternals(rest.getForEntity("/error", String.class));
    }

    private URI raw(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static void assertNoInternals(ResponseEntity<String> response) {
        assertThat(String.valueOf(response.getBody())).as("cuerpo de %s", response.getStatusCode())
                .doesNotContain("Exception", "org.springframework", "com.hogar", "at java.", "trace",
                        "SQL", "jackson", "Jackson");
    }

    /** Swagger es público: el documento no debe describir Actuator ni contener nada parecido a un secreto. */
    @Test
    void publicApiDocsExposeNothingSensitive() {
        ResponseEntity<String> docs = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docs.getBody()).doesNotContain("/actuator", "/livez", "/readyz", "jdbc:", "JWT_SECRET",
                "passwordHash", "password_hash", "tokenHash", "token_hash", "storageKey", "storage_key");
    }

    private ResponseEntity<String> login(String email, String password, String spoofedIp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", spoofedIp);
        headers.set("X-Real-IP", spoofedIp);
        headers.set("Forwarded", "for=" + spoofedIp);
        return rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", password), headers), String.class);
    }
}
