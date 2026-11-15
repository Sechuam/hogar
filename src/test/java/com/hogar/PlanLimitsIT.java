package com.hogar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.hogar.expense.FinanceApiSupport;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.CreateHouseholdRequest;
import com.hogar.household.dto.CreateInvitationRequest;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.household.dto.PlanResponse;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Límites del plan FREE de extremo a extremo (D-068), con los valores de producción salvo el espacio
 * de facturas, reducido para no subir 50 MB. El resto de IT usa límites holgados (application-test.yml).
 * El plan se cambia por SQL: no hay endpoint (no hay pagos).
 */
@TestPropertySource(properties = {
        "hogar.plans.free.max-members=4",
        "hogar.plans.free.max-owned-households=1",
        "hogar.plans.free.max-storage=" + PlanLimitsIT.MAX_STORAGE + "B",
        "hogar.plans.free.history-months=12"})
class PlanLimitsIT extends FinanceApiSupport {

    static final int MAX_STORAGE = 500;
    private static final byte[] PDF = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
            """.getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private Clock clock;

    @Test
    void freeHouseholdAcceptsUpToFourMembersAndPremiumLiftsTheLimit() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, users().create("Luis"), users().create("Eva"), users().create("Pablo"));
        String code = invitation(ana, id);
        TestUser quinto = users().create("Quinto");

        assertProblem(accept(quinto, code), HttpStatus.UNPROCESSABLE_ENTITY, "plan-member-limit");
        assertThat(jdbc.queryForObject("select uses from invitations where code = ?", Integer.class, code)).isZero();
        assertThat(plan(ana, id).usage().members()).isEqualTo(4);

        upgrade(id);
        assertThat(accept(quinto, code).getStatusCode()).isEqualTo(HttpStatus.OK);
        PlanResponse premium = plan(quinto, id);
        assertThat(premium.usage().members()).isEqualTo(5);
        assertThat(premium.limits().maxMembers()).isNull();
    }

    @Test
    void ownerOfAFreeHouseholdCannotCreateAnotherUntilItIsPremium() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID first = createHousehold(ana, SplitPolicy.EQUAL);
        // Ser MEMBER de otro hogar FREE no cuenta.
        UUID luisHousehold = createHousehold(luis, SplitPolicy.EQUAL);
        join(luis, luisHousehold, ana);

        assertProblem(createHouseholdCall(ana), HttpStatus.UNPROCESSABLE_ENTITY, "plan-household-limit");

        upgrade(first);
        assertThat(createHouseholdCall(ana).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(createHouseholdCall(ana), HttpStatus.UNPROCESSABLE_ENTITY, "plan-household-limit");
    }

    @Test
    void concurrentHouseholdCreationsOfTheSameUserNeverExceedTheLimit() throws Exception {
        for (int round = 0; round < 5; round++) {
            TestUser ana = users().create("Ana");
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResponseEntity<String>>> calls = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
                for (int i = 0; i < 4; i++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        return createHouseholdCall(ana);
                    }));
                }
                start.countDown();
                List<HttpStatusCode> statuses = new ArrayList<>();
                for (Future<ResponseEntity<String>> call : calls) {
                    statuses.add(call.get(30, TimeUnit.SECONDS).getStatusCode());
                }
                assertThat(statuses).as("ronda %d", round).containsOnlyOnce(HttpStatus.CREATED)
                        .filteredOn(st -> st != HttpStatus.CREATED).containsOnly(HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
    }

    @Test
    void invoicesStopFittingAtTheStorageLimitAndDeletingOneFreesSpace() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        int fitting = MAX_STORAGE / PDF.length;
        UUID[] expenses = new UUID[fitting + 1];
        for (int i = 0; i <= fitting; i++) {
            expenses[i] = createExpense(ana, id, expense("10.00", today(), ana.id())).id();
        }
        for (int i = 0; i < fitting; i++) {
            ResponseEntity<String> uploaded = upload(ana, id, expenses[i]);
            assertThat(uploaded.getStatusCode()).as(uploaded.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(plan(ana, id).usage().storageBytes()).isEqualTo((long) fitting * PDF.length);

        assertProblem(upload(ana, id, expenses[fitting]), HttpStatus.UNPROCESSABLE_ENTITY, "plan-storage-limit");
        assertThat(jdbc.queryForObject("select count(*) from invoices where household_id = ?", Integer.class, id))
                .isEqualTo(fitting);

        assertThat(call(HttpMethod.DELETE, expensePath(id, expenses[0]) + "/invoice", ana, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(upload(ana, id, expenses[fitting]).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void freeListingsShowTheLastTwelveMonthsButBalancesUseTheWholeHistory() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis);
        LocalDate historyStart = today().withDayOfMonth(1).minusMonths(11);
        ExpenseResponse old = createExpense(ana, id, expense("100.00", historyStart.minusDays(1), ana.id()));
        ExpenseResponse recent = createExpense(ana, id, expense("20.00", historyStart, ana.id()));
        UUID oldSettlement = settle(luis, ana, id, "10.00", historyStart.minusYears(2));
        UUID recentSettlement = settle(luis, ana, id, "5.00", today());

        assertThat(listExpenses(ana, id, "").content()).extracting(ExpenseResponse::id).containsExactly(recent.id());
        assertThat(listExpenses(ana, id, "?from=2000-01-01").content()).extracting(ExpenseResponse::id)
                .containsExactly(recent.id());
        assertThat(settlementIds(ana, id)).containsExactly(recentSettlement.toString());
        // El detalle sigue accesible: no se borra nada, solo se acota el listado.
        assertThat(getExpense(ana, id, old.id()).id()).isEqualTo(old.id());
        // Balances: (100 + 20) / 2 = 60 de Luis, menos 15 liquidados.
        assertThat(balanceOf(ana, id, luis)).isEqualByComparingTo("-45.00");
        assertThat(plan(ana, id).usage().historyStart()).isEqualTo(historyStart);

        upgrade(id);
        assertThat(listExpenses(ana, id, "").content()).extracting(ExpenseResponse::id)
                .containsExactly(recent.id(), old.id());
        assertThat(settlementIds(ana, id)).containsExactly(recentSettlement.toString(), oldSettlement.toString());
        assertThat(plan(ana, id).usage().historyStart()).isNull();
    }

    // --- Utilidades ---

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private void upgrade(UUID householdId) {
        jdbc.update("update households set plan = 'PREMIUM' where id = ?", householdId);
    }

    private PlanResponse plan(TestUser user, UUID householdId) {
        ResponseEntity<String> response = get(householdPath(householdId) + "/plan", user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, PlanResponse.class);
    }

    private ResponseEntity<String> createHouseholdCall(TestUser owner) {
        return call(HttpMethod.POST, "/api/households", owner, new CreateHouseholdRequest("Otro", null, null));
    }

    private String invitation(TestUser owner, UUID householdId) {
        ResponseEntity<String> response = call(HttpMethod.POST, householdPath(householdId) + "/invitations", owner,
                new CreateInvitationRequest(null, null));
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return read(response, InvitationResponse.class).code();
    }

    private ResponseEntity<String> accept(TestUser user, String code) {
        return call(HttpMethod.POST, "/api/invitations/" + code + "/accept", user, null);
    }

    private ResponseEntity<String> upload(TestUser user, UUID householdId, UUID expenseId) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_PDF);
        partHeaders.setContentDisposition(ContentDisposition.formData().name("file").filename("f.pdf").build());
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(PDF, partHeaders));
        HttpHeaders headers = user.bearer();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(expensePath(householdId, expenseId) + "/invoice", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private UUID settle(TestUser from, TestUser to, UUID householdId, String amount, LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromUserId", from.id());
        body.put("toUserId", to.id());
        body.put("amount", amount);
        body.put("date", date.toString());
        ResponseEntity<String> response = call(HttpMethod.POST, householdPath(householdId) + "/settlements", from, body);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(tree(response).get("id").asText());
    }

    private List<String> settlementIds(TestUser user, UUID householdId) {
        JsonNode page = tree(get(householdPath(householdId) + "/settlements", user));
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(s -> ids.add(s.get("id").asText()));
        return ids;
    }

    private BigDecimal balanceOf(TestUser viewer, UUID householdId, TestUser member) {
        JsonNode balances = tree(get(householdPath(householdId) + "/balances", viewer));
        for (JsonNode m : balances.get("members")) {
            if (m.get("userId").asText().equals(member.id().toString())) {
                return m.get("balance").decimalValue();
            }
        }
        throw new AssertionError("Sin balance para " + member.displayName());
    }
}
