package com.hogar.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers.TestUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Configuración errónea: {@code MANAGEMENT_PORT} igual al puerto público. Boot sirve entonces Actuator en
 * el contexto de la aplicación y la cadena abierta de {@link ManagementSecurityConfig} no se aplica
 * aunque Boot publica {@code local.management.port} como alias de {@code local.server.port}: las
 * métricas no quedan abiertas a Internet y la API sigue autenticando con JWT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ManagementOnApplicationPortIT extends AbstractIntegrationTest {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void samePort(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("management.server.port", () -> PORT);
    }

    @Test
    void metricsAreNotOpenToAnonymousUsers() {
        assertThat(rest.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void probesStayPublic() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/readyz", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Antes del arreglo la cadena abierta de gestión atrapaba <em>todas</em> las peticiones del puerto
     * público: la API se quedaba sin autenticación JWT y cada endpoint protegido respondía 500.
     */
    @Test
    void theApiKeepsAuthenticatingWithJwt() {
        TestUser user = users().create("Ana");

        ResponseEntity<String> me = rest.exchange("/api/users/me", HttpMethod.GET,
                new HttpEntity<>(user.bearer()), String.class);
        assertThat(me.getStatusCode()).as(me.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/api/households", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
