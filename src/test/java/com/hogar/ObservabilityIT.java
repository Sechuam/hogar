package com.hogar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hogar.shared.web.RequestIdFilter;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Observabilidad de punta a punta: cabecera {@code X-Request-Id} en respuestas y ProblemDetail,
 * y Actuator separado en el puerto de gestión (métricas fuera del puerto público).
 */
class ObservabilityIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void managementRunsOnItsOwnPort() {
        assertThat(managementPort).isPositive().isNotEqualTo(serverPort);
    }

    @Test
    void successfulResponseCarriesGeneratedRequestId() {
        TestUser user = users().create("Ana");

        ResponseEntity<String> response = rest.exchange("/api/households", HttpMethod.GET,
                new HttpEntity<>(user.bearer()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(UUID.fromString(requestIdHeader(response))).isNotNull();
    }

    @Test
    void unauthorizedProblemCarriesSameRequestIdAsHeader() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/households", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(problemRequestId(response)).isEqualTo(requestIdHeader(response));
    }

    @Test
    void notFoundProblemCarriesSameRequestIdAsHeader() throws Exception {
        TestUser user = users().create("Ana");

        ResponseEntity<String> response = rest.exchange("/api/households/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<>(user.bearer()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(problemRequestId(response)).isEqualTo(requestIdHeader(response));
    }

    @Test
    void validationProblemCarriesSameRequestIdAsHeader() throws Exception {
        TestUser user = users().create("Ana");
        HttpHeaders headers = user.bearer();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.exchange("/api/households", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).path("code").asText()).isEqualTo("validation-error");
        assertThat(problemRequestId(response)).isEqualTo(requestIdHeader(response));
    }

    @Test
    void validIncomingRequestIdIsEchoedInHeaderAndProblem() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestIdFilter.HEADER, "cliente-abc.123");

        ResponseEntity<String> response = rest.exchange("/api/households", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(requestIdHeader(response)).isEqualTo("cliente-abc.123");
        assertThat(problemRequestId(response)).isEqualTo("cliente-abc.123");
    }

    @Test
    void invalidIncomingRequestIdIsReplaced() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestIdFilter.HEADER, "x".repeat(65));

        ResponseEntity<String> response = rest.exchange("/api/households", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        String used = requestIdHeader(response);
        assertThat(UUID.fromString(used)).isNotNull();
        assertThat(problemRequestId(response)).isEqualTo(used);
    }

    @Test
    void prometheusMetricsAreServedOnManagementPortWithoutAuthentication() {
        // Una petición previa al puerto público garantiza que exista la métrica http_server_requests.
        rest.getForEntity("/livez", String.class);

        ResponseEntity<String> response = rest.getForEntity(managementUrl("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("http_server_requests_seconds_count");
        assertThat(response.getBody()).contains("application=\"hogar-api\"");
    }

    @Test
    void healthIsUpOnManagementPort() {
        ResponseEntity<String> response = rest.getForEntity(managementUrl("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusIsNotAvailableOnPublicPort() {
        ResponseEntity<String> anonymous = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        TestUser user = users().create("Ana");
        ResponseEntity<String> authenticated = rest.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(user.bearer()), String.class);
        assertThat(authenticated.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(authenticated.getBody()).doesNotContain("http_server_requests");

        ResponseEntity<String> metrics = rest.exchange("/actuator/metrics", HttpMethod.GET,
                new HttpEntity<>(user.bearer()), String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String managementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }

    private static String requestIdHeader(ResponseEntity<String> response) {
        String value = response.getHeaders().getFirst(RequestIdFilter.HEADER);
        assertThat(value).as("cabecera X-Request-Id").isNotBlank();
        return value;
    }

    private String problemRequestId(ResponseEntity<String> response) throws Exception {
        JsonNode requestId = json(response).path("requestId");
        assertThat(requestId.isTextual()).as("ProblemDetail.requestId en %s", response.getBody()).isTrue();
        return requestId.asText();
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }
}
