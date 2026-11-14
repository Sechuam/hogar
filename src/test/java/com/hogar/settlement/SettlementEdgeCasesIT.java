package com.hogar.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.UpdateMemberRoleRequest;
import com.hogar.settlement.dto.BalanceResponse;
import com.hogar.settlement.dto.MemberBalance;
import com.hogar.settlement.dto.SettlementResponse;
import com.hogar.settlement.dto.SuggestedTransfer;
import com.hogar.support.TestUsers.TestUser;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * QA Fase 5: casos límite de balances y liquidaciones (dinero, escenarios aleatorios, permisos de
 * ex-miembros, entradas hostiles y número de consultas).
 */
class SettlementEdgeCasesIT extends SettlementApiSupport {

    private static final String MAX = "999999999.99";

    @Autowired
    private EntityManagerFactory emf;

    // --- Dinero ---

    @Test
    void hugeAmountsAddUpExactlyWithScaleTwoAndWithoutExponentInTheJson() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla);
        for (int i = 0; i < 12; i++) {
            createExpense(ana, h, expense(MAX, MARCH_5, null)); // 333 333 333,33 × 3 exactos
        }
        settle(bob, h, bob.id(), ana.id(), MAX);

        BalanceResponse balances = balances(carla, h);

        assertThat(balances.members()).extracting(MemberBalance::userId, m -> m.paid().toPlainString(),
                        m -> m.owed().toPlainString(), m -> m.balance().toPlainString())
                .containsExactly(
                        tuple(ana.id(), "11999999999.88", "3999999999.96", "6999999999.93"),
                        tuple(bob.id(), "0.00", "3999999999.96", "-2999999999.97"),
                        tuple(carla.id(), "0.00", "3999999999.96", "-3999999999.96"));
        assertMatchesDb(h, balances);
        String body = get(householdPath(h) + "/balances", ana).getBody();
        assertThat(body).contains("11999999999.88").doesNotContainIgnoringCase("e+");
        List<SuggestedTransfer> transfers = suggested(ana, h).transfers();
        assertThat(transfers).extracting(t -> t.amount().toPlainString())
                .containsExactly("3999999999.96", "2999999999.97");
    }

    /**
     * Las deudas pueden superar el máximo de una liquidación (999 999 999,99) y entonces el pago
     * sugerido no se puede registrar tal cual: 400 en {@code amount}. Hay que trocearlo a mano.
     */
    @Test
    void suggestedTransferAboveTheMaximumSettlementCanBeRegistered() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);
        for (int i = 0; i < 3; i++) {
            createExpense(ana, h, expense(MAX, MARCH_5, null));
        }
        List<SuggestedTransfer> transfers = suggested(ana, h).transfers();
        assertThat(transfers).hasSize(1);

        applySuggestions(h, transfers, Map.of(ana.id(), ana, bob.id(), bob));

        assertThat(balances(ana, h).settled()).isTrue();
    }

    @Test
    void editingDeletingAndRecalculatingExpensesKeepsBalancesInSyncWithTheDatabase() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla);
        UUID e1 = createExpense(ana, h, expense("100.00", MARCH_5, null)).id();
        UUID e2 = createExpense(bob, h, expense("33.33", MARCH_5, null)).id();
        settle(carla, h, carla.id(), ana.id(), "20.00");
        assertMatchesDb(h, balances(ana, h));

        // PUT con otro importe y otro pagador recalcula el reparto.
        changePolicy(ana, h, SplitPolicy.PROPORTIONAL);
        oneOffIncome(ana, h, "1000.00", MARCH_5);
        oneOffIncome(carla, h, "3000.00", MARCH_5);
        ResponseEntity<String> edited = call(HttpMethod.PUT, expensePath(h, e1), ana,
                expense("80.01", MARCH_5, carla.id()));
        assertThat(edited.getStatusCode()).as(edited.getBody()).isEqualTo(HttpStatus.OK);
        assertMatchesDb(h, balances(bob, h));

        assertThat(call(HttpMethod.DELETE, expensePath(h, e2), bob, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        BalanceResponse after = balances(carla, h);
        assertMatchesDb(h, after);
        // 80,01 entre Ana (1000) y Carla (3000): 20,00 / 60,01 (Bob sin ingresos: 0).
        assertThat(byUser(after)).containsOnly(Map.entry(ana.id(), "-40.00"), Map.entry(bob.id(), "0.00"),
                Map.entry(carla.id(), "40.00"));
    }

    @Test
    void memberWhoLeavesAndComesBackAppearsOnceAndKeepsTheOldMovements() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);
        createExpense(ana, h, expense("10.00", MARCH_5, null));   // Bob debe 5
        leave(bob, h);
        createExpense(ana, h, expense("7.00", MARCH_5, null));    // solo Ana
        settle(ana, h, bob.id(), ana.id(), "1.00");                // la OWNER anota un pago del ex-miembro
        join(ana, h, bob);
        createExpense(bob, h, expense("4.00", MARCH_5, null));    // 2 / 2
        leave(bob, h);
        join(ana, h, bob);

        BalanceResponse balances = balances(bob, h);

        assertThat(balances.members()).extracting(MemberBalance::userId, MemberBalance::active,
                        m -> m.balance().toPlainString())
                .containsExactly(tuple(ana.id(), true, "2.00"),
                        tuple(bob.id(), true, "-2.00"));
        assertMatchesDb(h, balances);
        assertThat(suggested(bob, h).transfers()).containsExactly(
                new SuggestedTransfer(bob.id(), ana.id(), new BigDecimal("2.00")));
    }

    /**
     * Escenarios aleatorios por la API (semilla fija): gastos, ediciones, borrados, cambios de política,
     * bajas y vueltas, liquidaciones (incluso de más). Tras cada uno: Σ == 0, coincide con SQL, las
     * sugerencias son ≤ n − 1, sin autopagos, y aplicarlas salda el hogar.
     */
    @Test
    void randomScenariosAlwaysSettleWhenApplyingTheSuggestions() {
        Random random = new Random(20261114L);
        for (int scenario = 0; scenario < 4; scenario++) {
            runRandomScenario(random, scenario);
        }
    }

    private void runRandomScenario(Random random, int scenario) {
        int size = 2 + random.nextInt(4);
        List<TestUser> people = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            people.add(users().create("P" + scenario + "_" + i));
        }
        TestUser owner = people.get(0);
        UUID h = createHousehold(owner, random.nextBoolean() ? SplitPolicy.EQUAL : SplitPolicy.PROPORTIONAL);
        join(owner, h, people.subList(1, size).toArray(TestUser[]::new));
        Set<TestUser> active = new HashSet<>(people);
        Map<UUID, UUID> expensePayers = new LinkedHashMap<>();

        for (int step = 0; step < 25; step++) {
            List<TestUser> current = people.stream().filter(active::contains).toList();
            TestUser actor = current.get(random.nextInt(current.size()));
            switch (random.nextInt(8)) {
                case 0, 1, 2 -> {
                    UUID id = createExpense(actor, h, expense(randomAmount(random), MARCH_5, null)).id();
                    expensePayers.put(id, actor.id());
                }
                case 3 -> {
                    if (!expensePayers.isEmpty()) {
                        UUID id = new ArrayList<>(expensePayers.keySet()).get(random.nextInt(expensePayers.size()));
                        ResponseEntity<String> r = call(HttpMethod.PUT, expensePath(h, id), owner,
                                expense(randomAmount(random), MARCH_5, actor.id()));
                        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
                        expensePayers.put(id, actor.id());
                    }
                }
                case 4 -> {
                    if (!expensePayers.isEmpty() && random.nextBoolean()) {
                        UUID id = new ArrayList<>(expensePayers.keySet()).get(random.nextInt(expensePayers.size()));
                        ResponseEntity<String> r = call(HttpMethod.DELETE, expensePath(h, id), owner, null);
                        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
                        expensePayers.remove(id);
                    } else {
                        oneOffIncome(actor, h, randomAmount(random), MARCH_5);
                        changePolicy(owner, h, random.nextBoolean() ? SplitPolicy.EQUAL : SplitPolicy.PROPORTIONAL);
                    }
                }
                case 5 -> {
                    TestUser from = people.get(random.nextInt(size));
                    TestUser to = people.get(random.nextInt(size));
                    if (!from.equals(to)) {
                        settle(owner, h, from.id(), to.id(), randomAmount(random));
                    }
                }
                case 6 -> {
                    if (actor != owner) {
                        leave(actor, h);
                        active.remove(actor);
                    }
                }
                default -> {
                    List<TestUser> gone = people.stream().filter(p -> !active.contains(p)).toList();
                    if (!gone.isEmpty()) {
                        TestUser back = gone.get(random.nextInt(gone.size()));
                        join(owner, h, back);
                        active.add(back);
                    }
                }
            }
        }

        BalanceResponse balances = balances(owner, h);
        assertMatchesDb(h, balances);
        long nonZero = balances.members().stream().filter(m -> m.balance().signum() != 0).count();
        Map<UUID, BigDecimal> net = new HashMap<>();
        balances.members().forEach(m -> net.put(m.userId(), m.balance()));
        List<SuggestedTransfer> transfers = suggested(owner, h).transfers();
        assertThat(transfers.size()).as("escenario %d", scenario).isLessThanOrEqualTo((int) Math.max(0, nonZero - 1));
        for (SuggestedTransfer t : transfers) {
            assertThat(t.fromUserId()).isNotEqualTo(t.toUserId());
            assertThat(t.amount().signum()).isPositive();
            assertThat(t.amount().scale()).isEqualTo(2);
            assertThat(net.get(t.fromUserId()).signum()).as("solo pagan los deudores").isNegative();
            assertThat(net.get(t.toUserId()).signum()).as("solo cobran los acreedores").isPositive();
            // La OWNER las registra: quien paga puede ser un ex-miembro que ya no ve el hogar.
            settle(owner, h, t.fromUserId(), t.toUserId(), t.amount().toPlainString());
        }

        BalanceResponse after = balances(owner, h);
        assertThat(after.settled()).as("escenario %d", scenario).isTrue();
        assertMatchesDb(h, after);
        assertThat(suggested(owner, h).transfers()).isEmpty();
    }

    private static String randomAmount(Random random) {
        long cents = 1 + random.nextInt(random.nextInt(10) == 0 ? 100_000_000 : 20_000);
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    // --- Permisos y aislamiento ---

    @Test
    void formerMemberCannotRegisterReadOrDeleteEvenTheirOwnSettlements() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);
        SettlementResponse own = settle(bob, h, bob.id(), ana.id(), "3.00");
        leave(bob, h);

        assertProblem(deleteSettlement(bob, h, own.id()), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(settlementPath(h, own.id()), bob), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(settlementsPath(h), bob), HttpStatus.NOT_FOUND, "household-not-found");
        assertThat(listSettlements(ana, h, "").totalElements()).isEqualTo(1);
        // La receptora sí puede anularla.
        assertThat(deleteSettlement(ana, h, own.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void demotedOwnerCanStillDeleteWhatTheyRegisteredButNotOthers() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        TestUser dani = users().create("Dani");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla, dani);
        setRole(ana, h, dani, HouseholdRole.OWNER);
        SettlementResponse byDani = settle(dani, h, bob.id(), carla.id(), "1.00");
        SettlementResponse byBob = settle(bob, h, bob.id(), carla.id(), "2.00");
        setRole(ana, h, dani, HouseholdRole.MEMBER);

        assertProblem(deleteSettlement(dani, h, byBob.id()), HttpStatus.FORBIDDEN, "settlement-delete-forbidden");
        assertProblem(settleCall(dani, h, bob.id(), carla.id(), "1.00"), HttpStatus.FORBIDDEN,
                "settlement-forbidden");
        assertThat(deleteSettlement(dani, h, byDani.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void forbiddenIsCheckedBeforePartiesSoUnknownIdsAreNotRevealed() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla);
        TestUser outsider = users().create("Fuera");

        // Carla no es parte ni OWNER: 403 tanto si las partes existen como si no.
        assertProblem(settleCall(carla, h, UUID.randomUUID(), UUID.randomUUID(), "1.00"), HttpStatus.FORBIDDEN,
                "settlement-forbidden");
        assertProblem(settleCall(carla, h, outsider.id(), bob.id(), "1.00"), HttpStatus.FORBIDDEN,
                "settlement-forbidden");
        // El extraño, aunque se ponga como parte, no ve el hogar.
        assertProblem(settleCall(outsider, h, outsider.id(), ana.id(), "1.00"), HttpStatus.NOT_FOUND,
                "household-not-found");
        assertThat(listSettlements(ana, h, "").totalElements()).isZero();
    }

    @Test
    void userFilterWithAMemberOfAnotherHouseholdReturnsNothingFromThere() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser dani = users().create("Dani");
        TestUser eva = users().create("Eva");
        UUID mine = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, mine, bob);
        UUID other = createHousehold(dani, SplitPolicy.EQUAL);
        join(dani, other, eva);
        settle(eva, other, eva.id(), dani.id(), "9.00");
        settle(bob, mine, bob.id(), ana.id(), "1.00");

        assertThat(listSettlements(ana, mine, "?userId=" + eva.id()).content()).isEmpty();
        assertThat(listSettlements(ana, mine, "?userId=" + dani.id()).totalElements()).isZero();
        // Liquidación de mi hogar pedida por la ruta del otro: el otro hogar no existe para mí.
        assertProblem(get(settlementsPath(other) + "/suggested", ana), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(householdPath(other) + "/balances", ana), HttpStatus.NOT_FOUND, "household-not-found");
    }

    // --- Entradas hostiles ---

    private ResponseEntity<String> postRaw(TestUser user, UUID h, String json) {
        return call(HttpMethod.POST, settlementsPath(h), user, json);
    }

    private String body(UUID from, UUID to, String amountJson, String extra) {
        return "{\"fromUserId\":\"" + from + "\",\"toUserId\":\"" + to + "\",\"amount\":" + amountJson
                + ",\"date\":\"2026-03-05\"" + extra + "}";
    }

    @Test
    void scientificNotationAndShortDecimalsAreNormalisedToScaleTwo() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);

        for (String[] c : new String[][] {{"1e2", "100.00"}, {"1.5E+1", "15.00"}, {"7", "7.00"},
                {"0.1", "0.10"}}) {
            ResponseEntity<String> r = postRaw(bob, h, body(bob.id(), ana.id(), c[0], ""));
            assertThat(r.getStatusCode()).as(c[0] + " → " + r.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(read(r, SettlementResponse.class).amount().toPlainString()).isEqualTo(c[1]);
            assertThat(r.getBody()).contains("\"amount\":" + c[1] + ",");
        }
    }

    @ParameterizedTest
    // "0.010": @Digits cuenta la escala tal cual (más de 2 decimales aunque sean ceros), como en gastos.
    @ValueSource(strings = {"0.010", "12.500000", "1e18", "1E+17", "1e-3", "-0.01", "1e400", "\"doce\"", "true", "{}", "[1]", "null"})
    void hostileAmountsAreValidationErrorsOfTheAmountField(String amountJson) {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);

        assertValidationError(postRaw(bob, h, body(bob.id(), ana.id(), amountJson, "")), "amount");
    }

    @Test
    void hostileBodiesAreBadRequestsNeverServerErrors() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);
        String ok = body(bob.id(), ana.id(), "1.00", "");

        assertValidationError(postRaw(bob, h, ok.replace(bob.id().toString(), "no-es-uuid")), "fromUserId");
        assertValidationError(postRaw(bob, h, ok.replace("\"2026-03-05\"", "\"-0001-03-05\"")), "date");
        assertValidationError(postRaw(bob, h, ok.replace("\"2026-03-05\"", "\"+10000-01-01\"")), "date");
        assertValidationError(postRaw(bob, h, ok.replace("\"2026-03-05\"", "\"2026-03-05T10:00:00Z\"")), "date");
        assertValidationError(postRaw(bob, h, ok.replace("\"2026-03-05\"", "20260305")), "date");
        assertValidationError(postRaw(bob, h, body(bob.id(), ana.id(), "1.00", ",\"note\":\"Bizum\\u0000\"")),
                "note");
        // Jackson convierte escalares a texto: una nota numérica se acepta como "5" (no es un 500).
        assertThat(postRaw(bob, h, body(bob.id(), ana.id(), "1.00", ",\"note\":5")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        for (String broken : new String[] {"", "null", "[]", "{", "\"x\""}) {
            ResponseEntity<String> r = postRaw(bob, h, broken);
            assertThat(r.getStatusCode()).as("«" + broken + "» → " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<String> wrongType = call(HttpMethod.POST, settlementsPath(h), bob, null);
        assertThat(wrongType.getStatusCode().is4xxClientError()).as(wrongType.getBody()).isTrue();
        assertThat(listSettlements(ana, h, "").totalElements()).isEqualTo(1);
    }

    @Test
    void unknownAndServerOwnedFieldsAreIgnoredIncludingDistinctParties() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);
        UUID fakeId = UUID.randomUUID();

        ResponseEntity<String> spoofed = postRaw(bob, h, body(bob.id(), ana.id(), "4.00",
                ",\"id\":\"" + fakeId + "\",\"createdBy\":\"" + ana.id() + "\",\"currency\":\"USD\""
                        + ",\"createdAt\":\"2000-01-01T00:00:00Z\",\"distinctParties\":false,\"foo\":1"));

        assertThat(spoofed.getStatusCode()).as(spoofed.getBody()).isEqualTo(HttpStatus.CREATED);
        SettlementResponse created = read(spoofed, SettlementResponse.class);
        assertThat(created.id()).isNotEqualTo(fakeId);
        assertThat(created.createdBy()).isEqualTo(bob.id());
        assertThat(created.currency()).isEqualTo("EUR");
        assertThat(created.createdAt()).isNotEqualTo(java.time.Instant.parse("2000-01-01T00:00:00Z"));
        // Enviar distinctParties=true no salta la validación de partes distintas.
        assertValidationError(postRaw(bob, h, body(bob.id(), bob.id(), "4.00", ",\"distinctParties\":true")),
                "distinctParties");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/settlements/no-es-uuid", "/settlements/suggested/extra"})
    void malformedPathsAreClientErrors(String suffix) {
        TestUser ana = users().create("Ana");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        ResponseEntity<String> r = get(householdPath(h) + suffix, ana);
        assertThat(r.getStatusCode().is4xxClientError()).as(suffix + " → " + r.getBody()).isTrue();
        ResponseEntity<String> d = deleteSettlement(ana, h, null);
        assertThat(d.getStatusCode().is4xxClientError()).as(d.getBody()).isTrue();
    }

    // --- Rendimiento ---

    @Test
    void balancesAndSuggestionsUseAConstantNumberOfQueries() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla);
        createExpense(ana, h, expense("10.00", MARCH_5, null));
        settle(bob, h, bob.id(), ana.id(), "1.00");
        long fewBalances = statementsFor(() -> balances(ana, h));
        long fewSuggested = statementsFor(() -> suggested(ana, h));

        List<TestUser> more = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            more.add(users().create("Extra" + i));
        }
        join(ana, h, more.toArray(TestUser[]::new));
        List<TestUser> everyone = new ArrayList<>(List.of(ana, bob, carla));
        everyone.addAll(more);
        for (int i = 0; i < 150; i++) {
            TestUser payer = everyone.get(i % everyone.size());
            createExpense(payer, h, new ExpenseRequest(new BigDecimal(i + 1 + ".07"), "Compra " + i,
                    com.hogar.expense.domain.ExpenseCategory.OTHER, MARCH_5.plusDays(i % 20), null));
        }
        for (int i = 0; i < 30; i++) {
            TestUser from = everyone.get(i % everyone.size());
            settle(from, h, from.id(), ana.id().equals(from.id()) ? bob.id() : ana.id(), "0.5" + (i % 10));
        }
        leave(more.get(0), h);

        long manyBalances = statementsFor(() -> balances(ana, h));
        long manySuggested = statementsFor(() -> suggested(ana, h));

        assertThat(manyBalances).as("balances: 150 gastos y 8 miembros vs 1 gasto y 3").isEqualTo(fewBalances);
        assertThat(manySuggested).isEqualTo(fewSuggested);
        // membresía + hogar/miembros + 2 sumas de gastos + 2 de liquidaciones (+ margen)
        assertThat(manyBalances).isLessThanOrEqualTo(8);
        assertMatchesDb(h, balances(bob, h));
    }

    private void setRole(TestUser owner, UUID h, TestUser member, HouseholdRole role) {
        ResponseEntity<String> r = call(HttpMethod.PATCH, householdPath(h) + "/members/" + member.id(), owner,
                new UpdateMemberRoleRequest(role));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** Sentencias JDBC preparadas durante {@code action} (estadísticas de Hibernate activadas solo aquí). */
    private long statementsFor(Runnable action) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            action.run();
            return stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(wasEnabled);
        }
    }
}
