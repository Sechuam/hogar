package com.hogar.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hogar.income.dto.IncomeRequest;
import com.hogar.settlement.dto.SettlementRequest;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.task.domain.TaskRecurrence;
import com.hogar.task.dto.TaskRequest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * El documento OpenAPI publicado describe toda la API: se compara con los mappings reales de Spring MVC,
 * así que un endpoint nuevo sin documentar (o una ruta documentada que ya no existe) hace fallar el test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiDocumentIT extends AbstractIntegrationTest {

    private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode doc;
    private String rawDoc;

    @BeforeAll
    void downloadDocument() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        rawDoc = response.getBody();
        doc = objectMapper.readTree(rawDoc);
        // Copia para inspeccionarla a mano o publicarla (target/ no se versiona).
        Files.writeString(Path.of("target", "openapi.json"), response.getBody());
    }

    @Test
    void documentsExactlyTheApiEndpointsMappedBySpring() {
        Set<String> mapped = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getPackageName().startsWith("com.hogar")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            for (String pattern : info.getPatternValues()) {
                info.getMethodsCondition().getMethods()
                        .forEach(method -> mapped.add(method.name() + " " + pattern));
            }
        }
        Set<String> documented = new TreeSet<>();
        forEachOperation((key, operation) -> documented.add(key));

        assertThat(mapped).isNotEmpty().allMatch(op -> op.contains(" /api/"));
        assertThat(documented).isEqualTo(mapped);
    }

    @Test
    void declaresBearerSecurityGlobally() {
        JsonNode scheme = doc.at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("JWT");
        assertThat(doc.path("security").toString()).contains("bearerAuth");
    }

    @Test
    void authEndpointsArePublicAndTheRestRequireTheToken() {
        forEachOperation((key, operation) -> {
            JsonNode security = operation.get("security");
            if (key.contains(" /api/auth/")) {
                assertThat(security).as("%s debe ser público", key).isNotNull();
                assertThat(security.isEmpty()).as("%s debe ser público", key).isTrue();
            } else {
                // Sin "security" propio hereda el global (bearer).
                assertThat(security == null || security.toString().contains("bearerAuth"))
                        .as("%s debe exigir el token", key).isTrue();
                assertThat(operation.at("/responses/401").isMissingNode()).as("%s documenta 401", key).isFalse();
            }
        });
    }

    @Test
    void everyOperationHasSummaryTagAndDocumentedProblemDetailErrors() {
        Set<String> declaredTags = new TreeSet<>();
        doc.path("tags").forEach(tag -> declaredTags.add(tag.path("name").asText()));

        forEachOperation((key, operation) -> {
            assertThat(operation.path("summary").asText()).as("summary de %s", key).isNotBlank();
            assertThat(operation.path("tags")).as("tags de %s", key).isNotEmpty();
            operation.path("tags").forEach(tag -> assertThat(declaredTags).as("tag de %s", key).contains(tag.asText()));

            List<String> statuses = new ArrayList<>();
            operation.path("responses").fieldNames().forEachRemaining(statuses::add);
            assertThat(statuses).as("respuestas de %s", key)
                    .anyMatch(s -> s.startsWith("2"))
                    .anyMatch(s -> s.startsWith("4"));
            statuses.stream().filter(s -> s.startsWith("4") || s.startsWith("5")).forEach(status -> {
                JsonNode response = operation.path("responses").path(status);
                assertThat(response.path("description").asText()).as("%s %s", key, status).contains("`");
                assertThat(response.at("/content/application~1problem+json/schema/$ref").asText())
                        .as("%s %s usa ProblemDetail", key, status).isEqualTo(PROBLEM_REF);
                assertThat(response.at("/content/application~1problem+json/examples").isEmpty())
                        .as("%s %s tiene ejemplos", key, status).isFalse();
            });
        });
    }

    @Test
    void problemDetailSchemaDescribesCodeAndFieldErrors() {
        JsonNode properties = doc.at("/components/schemas/ProblemDetail/properties");
        assertThat(properties.fieldNames()).toIterable()
                .contains("type", "title", "status", "detail", "instance", "code", "errors");
        assertThat(properties.at("/errors/items/$ref").asText()).isEqualTo("#/components/schemas/FieldViolation");
        assertThat(doc.at("/components/schemas/FieldViolation/properties").fieldNames()).toIterable()
                .containsExactly("field", "message");
    }

    @Test
    void validationHelpersAreNotPartOfTheSchemas() {
        List<String> properties = new ArrayList<>();
        doc.at("/components/schemas").forEach(schema ->
                schema.path("properties").fieldNames().forEachRemaining(properties::add));

        assertThat(properties).isNotEmpty()
                .noneMatch(name -> name.endsWith("Valid"))
                .doesNotContain("distinctParties", "dueDateInRange", "rotationOrEmpty");
    }

    @Test
    void validationHelpersAreNotSerialized() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 3, 1);
        String json = objectMapper.writeValueAsString(List.of(
                new IncomeRequest(new BigDecimal("10.00"), "Nómina", date, true, null),
                new SettlementRequest(ana, bob, new BigDecimal("1.00"), date, null),
                new TaskRequest("Basura", null, null, TaskRecurrence.WEEKLY, date, List.of(ana, bob))));

        assertThat(json).doesNotContain("Valid\"", "distinctParties", "dueDateInRange", "rotationOrEmpty");
    }

    @Test
    void amountsAreDocumentedAsTwoDecimalNumbers() {
        JsonNode amount = doc.at("/components/schemas/ExpenseRequest/properties/amount");
        assertThat(amount.path("type").asText()).isEqualTo("number");
        assertThat(amount.path("description").asText()).contains("2 decimales");
        assertThat(amount.path("example").decimalValue()).isEqualByComparingTo("84.37");
        // Los ejemplos de importes conservan los 2 decimales en el JSON publicado (1850.00, no 1850.0).
        assertThat(rawDoc).contains("\"example\":1850.00", "\"example\":0.00").doesNotContain("\"example\":1850.0,");
    }

    @Test
    void invoiceUploadIsMultipartAndDownloadIsBinary() {
        JsonNode invoice = doc.at("/paths/~1api~1households~1{householdId}~1expenses~1{expenseId}~1invoice");
        JsonNode file = invoice.at("/post/requestBody/content/multipart~1form-data/schema/properties/file");
        assertThat(file.path("type").asText()).isEqualTo("string");
        assertThat(file.path("format").asText()).isEqualTo("binary");

        JsonNode download = invoice.at("/get/responses/200");
        for (String type : List.of("application/pdf", "image/jpeg", "image/png", "image/webp")) {
            assertThat(download.path("content").path(type).at("/schema/format").asText()).as(type).isEqualTo("binary");
        }
        assertThat(download.path("headers").fieldNames()).toIterable()
                .contains("Content-Disposition", "Content-Length", "Cache-Control", "X-Content-Type-Options");
    }

    @Test
    void swaggerUiIsServed() {
        ResponseEntity<String> ui = rest.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getBody()).contains("swagger-ui");
        assertThat(rest.getForEntity("/swagger-ui.html", String.class).getStatusCode().is4xxClientError()).isFalse();
    }

    /** Recorre las operaciones del documento como {@code "METHOD /ruta"}. */
    private void forEachOperation(BiConsumer<String, JsonNode> consumer) {
        doc.path("paths").fields().forEachRemaining(path ->
                StreamSupport.stream(((Iterable<Map.Entry<String, JsonNode>>) path.getValue()::fields).spliterator(), false)
                        .filter(op -> HTTP_METHODS.contains(op.getKey()))
                        .forEach(op -> consumer.accept(op.getKey().toUpperCase() + " " + path.getKey(), op.getValue())));
    }
}
