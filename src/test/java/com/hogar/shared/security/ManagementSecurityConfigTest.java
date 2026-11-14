package com.hogar.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * La cadena abierta de Actuator se elige por el <em>puerto local</em> de la conexión, que el cliente no
 * controla (a diferencia de {@code Host}, {@code X-Forwarded-Port} o la ruta).
 */
class ManagementSecurityConfigTest {

    @Test
    void onlyRequestsArrivingOnTheManagementPortMatch() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("local.server.port", "8080")
                .withProperty("local.management.port", "8081");

        assertThat(ManagementSecurityConfig.isManagementPort(onPort(8081), environment)).isTrue();
        assertThat(ManagementSecurityConfig.isManagementPort(onPort(8080), environment)).isFalse();
    }

    @Test
    void withoutManagementPortNothingMatches() {
        MockEnvironment environment = new MockEnvironment().withProperty("local.server.port", "8080");

        assertThat(ManagementSecurityConfig.isManagementPort(onPort(8080), environment)).isFalse();
        assertThat(ManagementSecurityConfig.isManagementPort(onPort(8081), environment)).isFalse();
    }

    /**
     * {@code MANAGEMENT_PORT} = puerto público: Boot publica {@code local.management.port} como alias de
     * {@code local.server.port}. La cadena abierta no debe atrapar el tráfico de la API.
     */
    @Test
    void managementPortEqualToTheServerPortNeverMatches() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("local.server.port", "8080")
                .withProperty("local.management.port", "8080");

        assertThat(ManagementSecurityConfig.isManagementPort(onPort(8080), environment)).isFalse();
    }

    @Test
    void forgedPortHeadersDoNotMatter() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("local.server.port", "8080")
                .withProperty("local.management.port", "8081");
        MockHttpServletRequest request = onPort(8080);
        request.addHeader("X-Forwarded-Port", "8081");
        request.addHeader("Host", "localhost:8081");
        request.setServerPort(8081);

        assertThat(ManagementSecurityConfig.isManagementPort(request, environment)).isFalse();
    }

    private static MockHttpServletRequest onPort(int localPort) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setLocalPort(localPort);
        return request;
    }
}
