package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.SplitPolicy;
import com.hogar.support.TestUsers.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * QA Fase 2: entradas hostiles o absurdas en ingresos y gastos. Lo que se exige es un 4xx con
 * ProblemDetail (nunca 500 ni filtrar detalles internos).
 */
class FinanceRobustnessIT extends FinanceApiSupport {

    private record Couple(UUID id, TestUser ana, TestUser luis) {
    }

    private Couple couple() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis);
        return new Couple(id, ana, luis);
    }

    private ResponseEntity<String> raw(HttpMethod method, String path, TestUser user, String body) {
        HttpHeaders headers = user.bearer();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String expenseJson(String description, String date) {
        return """
                {"amount": 10, "description": %s, "category": "GROCERIES", "date": %s}
                """.formatted(description, date);
    }

    private void assertClientError(ResponseEntity<String> r) {
        assertThat(r.getStatusCode().is4xxClientError()).as(r.getStatusCode() + " " + r.getBody()).isTrue();
        assertThat(tree(r).path("code").asText()).isNotBlank();
        assertThat(r.getBody()).doesNotContainIgnoringCase("exception").doesNotContainIgnoringCase("org.");
    }

    @Test
    void absurdDatesAreRejectedOrStoredButNeverFail() {
        Couple c = couple();
        for (String date : new String[] {"\"2026-02-30\"", "\"2026-13-01\"", "\"01/02/2026\"",
                "\"\"", "null", "true", "{}"}) {
            assertClientError(raw(HttpMethod.POST, expensesPath(c.id()), c.ana(), expenseJson("\"x\"", date)));
        }
        // Límites del rango admitido (D-047): años 1..9999.
        for (String date : new String[] {"\"0001-01-01\"", "\"9999-12-31\""}) {
            ResponseEntity<String> r = raw(HttpMethod.POST, expensesPath(c.id()), c.ana(), expenseJson("\"x\"", date));
            assertThat(r.getStatusCode()).as(date + " " + r.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        // Fuera de rango: a.C. (el driver JDBC las lee corruptas) y años de 5 cifras → 400 del campo.
        for (String date : new String[] {"\"0000-02-29\"", "\"-0100-03-31\"", "\"+10000-01-01\""}) {
            assertValidationError(raw(HttpMethod.POST, expensesPath(c.id()), c.ana(), expenseJson("\"x\"", date)),
                    "date");
            assertValidationError(raw(HttpMethod.POST, incomesPath(c.id()), c.ana(),
                    "{\"amount\": 10, \"concept\": \"x\", \"date\": " + date + ", \"recurring\": false}"), "date");
        }
        assertDbMoneyInvariant(c.id());
    }

    @Test
    void unicodeBlankTextAndHugePagesAreBadRequests() {
        Couple c = couple();
        String emSpaces = "\"\u2003\u3000\u2003\"";
        assertValidationError(raw(HttpMethod.POST, expensesPath(c.id()), c.ana(),
                expenseJson(emSpaces, "\"2026-03-01\"")), "description");
        assertValidationError(raw(HttpMethod.POST, incomesPath(c.id()), c.ana(),
                "{\"amount\": 10, \"concept\": " + emSpaces + ", \"date\": \"2026-03-01\", \"recurring\": false}"),
                "concept");
        for (String path : new String[] {expensesPath(c.id()), incomesPath(c.id())}) {
            ResponseEntity<String> r = raw(HttpMethod.GET, path + "?page=2147483647&size=100", c.ana(), null);
            assertThat(r.getStatusCode()).as(path + " " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void numericDatesAreRejected() {
        Couple c = couple();
        assertValidationError(raw(HttpMethod.POST, expensesPath(c.id()), c.ana(), expenseJson("\"x\"", "20260101")),
                "date");
        assertValidationError(raw(HttpMethod.POST, incomesPath(c.id()), c.ana(),
                "{\"amount\": 10, \"concept\": \"x\", \"date\": 20260101, \"recurring\": false}"), "date");
    }

    @Test
    void controlCharactersAndOversizedTextNeverFail() {
        Couple c = couple();
        // El byte NUL no cabe en un text/varchar de PostgreSQL.
        ResponseEntity<String> nul = raw(HttpMethod.POST, expensesPath(c.id()), c.ana(),
                expenseJson("\"luz\\u0000agua\"", "\"2026-03-01\""));
        assertThat(nul.getStatusCode()).as(nul.getBody()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertClientError(nul);

        ResponseEntity<String> nulIncome = raw(HttpMethod.POST, incomesPath(c.id()), c.ana(), """
                {"amount": 10, "concept": "a\\u0000b", "date": "2026-03-01", "recurring": false}
                """);
        assertClientError(nulIncome);

        assertValidationError(raw(HttpMethod.POST, expensesPath(c.id()), c.ana(),
                expenseJson("\"" + "x".repeat(201) + "\"", "\"2026-03-01\"")), "description");
        // 200 emojis = 400 unidades UTF-16: Bean Validation cuenta unidades UTF-16 → 400, nunca 500.
        assertClientError(raw(HttpMethod.POST, expensesPath(c.id()), c.ana(),
                expenseJson("\"" + "😀".repeat(200) + "\"", "\"2026-03-01\"")));
        assertValidationError(raw(HttpMethod.POST, expensesPath(c.id()), c.ana(), expenseJson("\"   \"",
                "\"2026-03-01\"")), "description");
    }

    @Test
    void nulCharacterInTextIsAValidationErrorOfThatField() {
        Couple c = couple();
        assertValidationError(raw(HttpMethod.POST, expensesPath(c.id()), c.ana(),
                expenseJson("\"luz\\u0000agua\"", "\"2026-03-01\"")), "description");
        assertValidationError(raw(HttpMethod.POST, incomesPath(c.id()), c.ana(), """
                {"amount": 10, "concept": "a\\u0000b", "date": "2026-03-01", "recurring": false}
                """), "concept");
    }

    @Test
    void malformedPathAndQueryParametersAreBadRequests() {
        Couple c = couple();
        String base = householdPath(c.id());
        for (String path : new String[] {
                "/api/households/not-a-uuid/expenses",
                base + "/expenses/not-a-uuid",
                base + "/expenses?from=2026-02-30",
                base + "/expenses?category=CAVIAR",
                base + "/expenses?paidBy=nope",
                base + "/expenses?page=abc",
                base + "/expenses?size=" + Long.MAX_VALUE,
                base + "/incomes?month=2026-13",
                base + "/incomes?month=2026-1",
                base + "/incomes/summary",
                base + "/incomes/summary?month=",
                base + "/incomes/summary?month=abc",
                base + "/incomes?userId=1",
                base + "/incomes?size=101",
                base + "/incomes?size=0",
                base + "/incomes?page=-1",
                base + "/expenses?size=101",
                base + "/expenses?page=-1",
                base + "/expenses?from=2026-03-02&to=2026-03-01"}) {
            ResponseEntity<String> r = get(path, c.ana());
            assertThat(r.getStatusCode()).as(path + " → " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertClientError(r);
        }
    }

    @Test
    void incomeEdgeValuesAreValidated() {
        Couple c = couple();
        String path = incomesPath(c.id());
        for (String body : new String[] {
                "{\"amount\": 0, \"concept\": \"x\", \"date\": \"2026-03-01\", \"recurring\": false}",
                "{\"amount\": 1.001, \"concept\": \"x\", \"date\": \"2026-03-01\", \"recurring\": false}",
                "{\"amount\": 1E+12, \"concept\": \"x\", \"date\": \"2026-03-01\", \"recurring\": false}",
                "{\"amount\": 10, \"concept\": \"x\", \"date\": \"2026-03-01\", \"recurring\": false,"
                        + " \"endDate\": \"2026-04-01\"}",
                "{\"amount\": 10, \"concept\": \"x\", \"date\": \"2026-03-01\", \"recurring\": true,"
                        + " \"endDate\": \"2026-02-28\"}",
                "{\"amount\": 10, \"concept\": \"x\", \"date\": \"2026-03-01\", \"recurring\": \"maybe\"}",
                "{\"amount\": 10, \"concept\": \"x\", \"date\": null, \"recurring\": true, \"endDate\": \"2026-04-01\"}"}) {
            ResponseEntity<String> r = raw(HttpMethod.POST, path, c.ana(), body);
            assertThat(r.getStatusCode()).as(body + " → " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        // recurring ausente = false (primitivo): defined behaviour, no 500.
        ResponseEntity<String> noFlag = raw(HttpMethod.POST, path, c.ana(),
                "{\"amount\": 10, \"concept\": \"x\", \"date\": \"2026-03-01\"}");
        assertThat(noFlag.getStatusCode()).as(noFlag.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(tree(noFlag).path("recurring").asBoolean(true)).isFalse();
        // Recurrente de un solo día (endDate == date) es válido.
        assertThat(raw(HttpMethod.POST, path, c.ana(), "{\"amount\": 10, \"concept\": \"x\", \"date\": \"2026-03-01\","
                + " \"recurring\": true, \"endDate\": \"2026-03-01\"}").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void foreignPayerIdThatIsNotEvenAUserIsAnUnprocessableEntity() {
        Couple c = couple();
        ResponseEntity<String> r = call(HttpMethod.POST, expensesPath(c.id()), c.ana(),
                expense("5.00", java.time.LocalDate.of(2026, 3, 1), UUID.randomUUID()));
        assertProblem(r, HttpStatus.UNPROCESSABLE_ENTITY, "payer-not-member");
    }
}
