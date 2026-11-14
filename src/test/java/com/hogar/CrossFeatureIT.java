package com.hogar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hogar.shared.web.RequestIdFilter;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Casos límite que cruzan features: un miembro con datos en todas (ingreso, gasto con factura, tarea,
 * liquidación) que sale del hogar, el último miembro que abandona un hogar con historial, un usuario
 * que pertenece a varios hogares (su token pasa el guard de ambos, así que el aislamiento depende de
 * que cada recurso se busque por {@code (id, householdId)}) y el {@code requestId} en los errores de
 * cada feature.
 */
class CrossFeatureIT extends AbstractIntegrationTest {

    private static final byte[] PDF = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** Ids de los recursos que un usuario crea en un hogar, uno por feature. */
    private record Footprint(UUID householdId, String incomeId, String expenseId, String taskId, String settlementId) {
    }

    @Test
    void memberWithDataInEveryFeatureLeavesAndTheHouseholdStaysConsistent() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID household = createHousehold(ana, "Piso compartido");
        join(ana, household, luis);
        Footprint luisData = footprint(luis, household, ana);

        assertStatus(call(HttpMethod.POST, base(household) + "/leave", luis, null), HttpStatus.NO_CONTENT);

        // El ex-miembro pierde el acceso a todo, incluida la factura de un gasto que pagó él.
        for (String path : List.of(base(household), base(household) + "/balances",
                base(household) + "/incomes/" + luisData.incomeId(),
                base(household) + "/expenses/" + luisData.expenseId(),
                base(household) + "/expenses/" + luisData.expenseId() + "/invoice",
                base(household) + "/tasks/" + luisData.taskId(),
                base(household) + "/settlements/" + luisData.settlementId())) {
            assertProblem(call(HttpMethod.GET, path, luis, null), HttpStatus.NOT_FOUND, "household-not-found");
        }
        assertThat(json(call(HttpMethod.GET, "/api/households", luis, null)).toString())
                .doesNotContain(household.toString());

        // Los que quedan conservan su historia: gasto, factura, tarea e ingreso del que se fue.
        assertStatus(call(HttpMethod.GET, base(household) + "/expenses/" + luisData.expenseId(), ana, null),
                HttpStatus.OK);
        ResponseEntity<byte[]> invoice = rest.exchange(base(household) + "/expenses/" + luisData.expenseId()
                + "/invoice", HttpMethod.GET, new HttpEntity<>(ana.bearer()), byte[].class);
        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(invoice.getBody()).isEqualTo(PDF);
        JsonNode task = json(call(HttpMethod.GET, base(household) + "/tasks/" + luisData.taskId(), ana, null));
        assertThat(task.path("assigneeId").asText()).isEqualTo(luis.id().toString());
        assertStatus(call(HttpMethod.GET, base(household) + "/incomes/" + luisData.incomeId(), ana, null),
                HttpStatus.OK);

        // Balance: Luis sigue apareciendo (inactivo) con su deuda y Σ == 0.
        JsonNode balances = json(call(HttpMethod.GET, base(household) + "/balances", ana, null));
        JsonNode luisBalance = memberBalance(balances, luis.id());
        assertThat(luisBalance.path("active").asBoolean()).isFalse();
        assertThat(sumOfBalances(balances)).isEqualByComparingTo("0.00");
        // Pagó 90.00, debe 45.00 y recibió 10.00 de Ana: acreedor de 35.00.
        assertThat(luisBalance.path("balance").decimalValue()).isEqualByComparingTo("35.00");

        // Se puede saldar con el ex-miembro siguiendo la sugerencia: el hogar queda a cero.
        JsonNode transfer = json(call(HttpMethod.GET, base(household) + "/settlements/suggested", ana, null))
                .path("transfers").get(0);
        assertThat(transfer.path("toUserId").asText()).isEqualTo(luis.id().toString());
        assertStatus(call(HttpMethod.POST, base(household) + "/settlements", ana, Map.of(
                "fromUserId", ana.id().toString(), "toUserId", luis.id().toString(),
                "amount", transfer.path("amount").asText(), "date", "2026-03-20")), HttpStatus.CREATED);
        JsonNode settled = json(call(HttpMethod.GET, base(household) + "/balances", ana, null));
        settled.path("members").forEach(m -> assertThat(m.path("balance").decimalValue()).isZero());
    }

    @Test
    void lastMemberLeavingKeepsTheHistoryButNobodyCanReachIt() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID household = createHousehold(ana, "Piso que se vacía");
        join(ana, household, luis);
        Footprint anaData = footprint(ana, household, luis);
        String code = json(call(HttpMethod.POST, base(household) + "/invitations", ana, Map.of())).path("code").asText();
        assertStatus(call(HttpMethod.POST, base(household) + "/leave", luis, null), HttpStatus.NO_CONTENT);

        assertStatus(call(HttpMethod.POST, base(household) + "/leave", ana, null), HttpStatus.NO_CONTENT);

        for (TestUser former : List.of(ana, luis)) {
            assertProblem(call(HttpMethod.GET, base(household) + "/expenses/" + anaData.expenseId(), former, null),
                    HttpStatus.NOT_FOUND, "household-not-found");
            assertProblem(call(HttpMethod.GET, base(household) + "/balances", former, null),
                    HttpStatus.NOT_FOUND, "household-not-found");
        }
        // Una invitación viva de un hogar vacío ya no sirve: nadie podría administrarlo.
        TestUser eva = users().create("Eva");
        assertProblem(call(HttpMethod.POST, "/api/invitations/" + code + "/accept", eva, null),
                HttpStatus.NOT_FOUND, "invitation-not-found");
        // Baja lógica: nada se borra (los importes siguen siendo historia de quienes estuvieron).
        assertThat(count("select count(*) from shared_expenses where household_id = ?", household)).isEqualTo(1);
        assertThat(count("select count(*) from invoices where household_id = ?", household)).isEqualTo(1);
        assertThat(count("select count(*) from tasks where household_id = ?", household)).isEqualTo(1);
        assertThat(count("select count(*) from settlements where household_id = ?", household)).isEqualTo(1);
        assertThat(count("select count(*) from incomes where household_id = ?", household)).isEqualTo(1);
    }

    @Test
    void userInTwoHouseholdsCannotReachResourcesOfOneThroughTheOther() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser marta = users().create("Marta");
        UUID first = createHousehold(ana, "Piso de Ana");
        UUID second = createHousehold(ana, "Casa del pueblo");
        join(ana, first, luis);
        join(ana, second, marta);
        Footprint inFirst = footprint(ana, first, luis);

        // El mismo token pasa el guard de ambos hogares: el recurso del primero no existe en el segundo.
        String b = base(second);
        List<Object[]> attempts = List.of(
                new Object[] {HttpMethod.GET, b + "/incomes/" + inFirst.incomeId(), null, "income-not-found"},
                new Object[] {HttpMethod.PUT, b + "/incomes/" + inFirst.incomeId(), incomeBody(), "income-not-found"},
                new Object[] {HttpMethod.DELETE, b + "/incomes/" + inFirst.incomeId(), null, "income-not-found"},
                new Object[] {HttpMethod.GET, b + "/expenses/" + inFirst.expenseId(), null, "expense-not-found"},
                new Object[] {HttpMethod.PUT, b + "/expenses/" + inFirst.expenseId(), expenseBody(ana), "expense-not-found"},
                new Object[] {HttpMethod.DELETE, b + "/expenses/" + inFirst.expenseId(), null, "expense-not-found"},
                new Object[] {HttpMethod.GET, b + "/expenses/" + inFirst.expenseId() + "/invoice", null, "expense-not-found"},
                new Object[] {HttpMethod.GET, b + "/expenses/" + inFirst.expenseId() + "/invoice/metadata", null,
                        "expense-not-found"},
                new Object[] {HttpMethod.DELETE, b + "/expenses/" + inFirst.expenseId() + "/invoice", null,
                        "expense-not-found"},
                new Object[] {HttpMethod.GET, b + "/tasks/" + inFirst.taskId(), null, "task-not-found"},
                new Object[] {HttpMethod.PUT, b + "/tasks/" + inFirst.taskId(), Map.of("title", "x", "recurrence", "NONE"),
                        "task-not-found"},
                new Object[] {HttpMethod.POST, b + "/tasks/" + inFirst.taskId() + "/complete", null, "task-not-found"},
                new Object[] {HttpMethod.GET, b + "/tasks/" + inFirst.taskId() + "/completions", null, "task-not-found"},
                new Object[] {HttpMethod.DELETE, b + "/tasks/" + inFirst.taskId(), null, "task-not-found"},
                new Object[] {HttpMethod.GET, b + "/settlements/" + inFirst.settlementId(), null, "settlement-not-found"},
                new Object[] {HttpMethod.DELETE, b + "/settlements/" + inFirst.settlementId(), null,
                        "settlement-not-found"});
        for (Object[] attempt : attempts) {
            ResponseEntity<String> response = call((HttpMethod) attempt[0], (String) attempt[1], ana, attempt[2]);
            assertProblem(response, HttpStatus.NOT_FOUND, (String) attempt[3]);
        }

        // Personas del otro hogar tampoco valen como pagador, responsable o parte de una liquidación.
        assertProblem(call(HttpMethod.POST, b + "/expenses", ana, expenseBody(luis)),
                HttpStatus.UNPROCESSABLE_ENTITY, "payer-not-member");
        assertProblem(call(HttpMethod.POST, b + "/tasks", ana,
                Map.of("title", "x", "recurrence", "NONE", "assigneeId", luis.id().toString())),
                HttpStatus.UNPROCESSABLE_ENTITY, "assignee-not-member");
        assertProblem(call(HttpMethod.POST, b + "/settlements", ana, Map.of("fromUserId", ana.id().toString(),
                "toUserId", luis.id().toString(), "amount", "5.00", "date", "2026-03-20")),
                HttpStatus.UNPROCESSABLE_ENTITY, "settlement-party-not-member");

        // Nada del primer hogar se ha tocado ni aparece en los listados o balances del segundo.
        assertStatus(call(HttpMethod.GET, base(first) + "/expenses/" + inFirst.expenseId(), ana, null), HttpStatus.OK);
        assertStatus(call(HttpMethod.GET, base(first) + "/expenses/" + inFirst.expenseId() + "/invoice/metadata",
                ana, null), HttpStatus.OK);
        assertStatus(call(HttpMethod.GET, base(first) + "/tasks/" + inFirst.taskId(), ana, null), HttpStatus.OK);
        assertStatus(call(HttpMethod.GET, base(first) + "/settlements/" + inFirst.settlementId(), ana, null),
                HttpStatus.OK);
        assertStatus(call(HttpMethod.GET, base(first) + "/incomes/" + inFirst.incomeId(), ana, null), HttpStatus.OK);
        for (String listing : List.of("/incomes", "/expenses", "/tasks", "/settlements", "/balances")) {
            String body = call(HttpMethod.GET, b + listing, ana, null).getBody();
            assertThat(body).as(listing).doesNotContain(inFirst.expenseId(), inFirst.incomeId(), inFirst.taskId(),
                    inFirst.settlementId(), luis.id().toString());
        }
        // Y Marta, miembro solo del segundo, no ve el primero.
        assertProblem(call(HttpMethod.GET, base(first) + "/expenses/" + inFirst.expenseId(), marta, null),
                HttpStatus.NOT_FOUND, "household-not-found");
    }

    /** Muestreo: un error de cada feature lleva en el ProblemDetail el mismo {@code requestId} de la cabecera. */
    @Test
    void errorsOfEveryFeatureCarryTheRequestIdOfTheHeader() {
        TestUser ana = users().create("Ana");
        UUID household = createHousehold(ana, "Piso");
        String missing = UUID.randomUUID().toString();
        List<ResponseEntity<String>> errors = new ArrayList<>();
        errors.add(call(HttpMethod.POST, "/api/auth/login", null, Map.of("email", "", "password", "")));
        errors.add(call(HttpMethod.POST, "/api/auth/refresh", null, Map.of("refreshToken", "no-existe")));
        errors.add(call(HttpMethod.GET, "/api/users/me", null, null));
        errors.add(call(HttpMethod.POST, "/api/invitations/NOEXISTE/accept", ana, null));
        errors.add(call(HttpMethod.GET, base(household) + "/incomes/" + missing, ana, null));
        errors.add(call(HttpMethod.POST, base(household) + "/expenses", ana, Map.of("amount", "-1")));
        errors.add(call(HttpMethod.GET, base(household) + "/expenses/" + missing + "/invoice", ana, null));
        errors.add(call(HttpMethod.GET, base(household) + "/tasks?size=1000", ana, null));
        errors.add(call(HttpMethod.GET, base(household) + "/settlements/" + missing, ana, null));
        errors.add(call(HttpMethod.GET, base(household) + "/incomes/summary?month=2026-13", ana, null));
        errors.add(call(HttpMethod.PUT, base(household), ana, Map.of()));

        for (ResponseEntity<String> response : errors) {
            assertThat(response.getStatusCode().isError()).as(response.getBody()).isTrue();
            String header = response.getHeaders().getFirst(RequestIdFilter.HEADER);
            assertThat(header).as("X-Request-Id de %s", response.getBody()).isNotBlank();
            assertThat(json(response).path("requestId").asText()).as(response.getBody()).isEqualTo(header);
            assertThat(json(response).path("code").asText()).isNotBlank();
        }
    }

    // ---------------------------------------------------------------- escenario

    /**
     * {@code actor} registra un ingreso, paga un gasto de 90.00 a partes iguales con {@code other} con
     * factura adjunta, se asigna una tarea y recibe una liquidación de 10.00 de {@code other}.
     */
    private Footprint footprint(TestUser actor, UUID household, TestUser other) {
        String income = json(created(call(HttpMethod.POST, base(household) + "/incomes", actor, incomeBody())))
                .path("id").asText();
        String expense = json(created(call(HttpMethod.POST, base(household) + "/expenses", actor,
                Map.of("amount", "90.00", "description", "Luz", "category", "ELECTRICITY", "date", "2026-03-10",
                        "paidBy", actor.id().toString())))).path("id").asText();
        created(uploadInvoice(actor, household, expense));
        String task = json(created(call(HttpMethod.POST, base(household) + "/tasks", actor,
                Map.of("title", "Bajar la basura", "recurrence", "WEEKLY", "dueDate", "2026-03-16",
                        "assigneeId", actor.id().toString())))).path("id").asText();
        String settlement = json(created(call(HttpMethod.POST, base(household) + "/settlements", other,
                Map.of("fromUserId", other.id().toString(), "toUserId", actor.id().toString(),
                        "amount", "10.00", "date", "2026-03-15")))).path("id").asText();
        return new Footprint(household, income, expense, task, settlement);
    }

    private static Map<String, Object> incomeBody() {
        return Map.of("amount", "1500.00", "concept", "Nómina", "date", "2026-03-01");
    }

    private static Map<String, Object> expenseBody(TestUser payer) {
        return Map.of("amount", "10.00", "description", "x", "category", "RENT", "date", "2026-03-01",
                "paidBy", payer.id().toString());
    }

    private ResponseEntity<String> uploadInvoice(TestUser user, UUID household, String expenseId) {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        part.setContentDisposition(ContentDisposition.formData().name("file").filename("luz.pdf").build());
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(PDF, part));
        HttpHeaders headers = user.bearer();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(base(household) + "/expenses/" + expenseId + "/invoice", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private UUID createHousehold(TestUser owner, String name) {
        return UUID.fromString(json(created(call(HttpMethod.POST, "/api/households", owner, Map.of("name", name))))
                .path("id").asText());
    }

    private void join(TestUser owner, UUID household, TestUser user) {
        String code = json(created(call(HttpMethod.POST, base(household) + "/invitations", owner, Map.of())))
                .path("code").asText();
        assertStatus(call(HttpMethod.POST, "/api/invitations/" + code + "/accept", user, null), HttpStatus.OK);
    }

    // ---------------------------------------------------------------- utilidades

    private ResponseEntity<String> call(HttpMethod method, String path, TestUser user, Object body) {
        HttpHeaders headers = user != null ? user.bearer() : new HttpHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String base(UUID household) {
        return "/api/households/" + household;
    }

    private static ResponseEntity<String> created(ResponseEntity<String> response) {
        return assertStatus(response, HttpStatus.CREATED);
    }

    private static ResponseEntity<String> assertStatus(ResponseEntity<String> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        return response;
    }

    private void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        assertThat(json(response).path("code").asText()).as(response.getBody()).isEqualTo(code);
    }

    private JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("No es JSON: " + response.getBody(), e);
        }
    }

    private static JsonNode memberBalance(JsonNode balances, UUID userId) {
        for (JsonNode member : balances.path("members")) {
            if (member.path("userId").asText().equals(userId.toString())) {
                return member;
            }
        }
        throw new AssertionError("Sin balance para " + userId + " en " + balances);
    }

    private static BigDecimal sumOfBalances(JsonNode balances) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode member : balances.path("members")) {
            sum = sum.add(member.path("balance").decimalValue());
        }
        return sum;
    }

    private int count(String sql, UUID household) {
        Integer value = jdbc.queryForObject(sql, Integer.class, household);
        return value == null ? 0 : value;
    }
}
