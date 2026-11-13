package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.income.dto.IncomeRequest;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * QA Fase 2: qué ingresos cuentan en un reparto PROPORTIONAL en los bordes del mes natural de la
 * fecha del gasto (día 1, último día, 29-feb, recurrentes que empiezan o acaban a mitad de mes) y
 * qué ingresos no deben contar nunca (ex-miembros, otro hogar del mismo usuario).
 */
class ProportionalMonthEdgesIT extends FinanceApiSupport {

    private record Couple(UUID id, TestUser ana, TestUser luis) {
    }

    private Couple couple() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis);
        return new Couple(id, ana, luis);
    }

    private void recurring(TestUser user, UUID householdId, String amount, LocalDate start, LocalDate end) {
        createIncome(user, householdId, new IncomeRequest(new BigDecimal(amount), "Recurrente", start, true, end));
    }

    private Map<UUID, String> weights(ExpenseResponse e) {
        return e.shares().stream().collect(java.util.stream.Collectors.toMap(
                ExpenseResponse.ShareResponse::userId, s -> s.weight().toPlainString()));
    }

    @Test
    void firstAndLastDayOfTheMonthUseExactlyThatMonthsIncomes() {
        Couple c = couple();
        oneOffIncome(c.ana(), c.id(), "3000.00", LocalDate.of(2026, 6, 30)); // último día de junio
        oneOffIncome(c.luis(), c.id(), "1000.00", LocalDate.of(2026, 7, 1)); // primer día de julio
        oneOffIncome(c.luis(), c.id(), "1000.00", LocalDate.of(2026, 6, 1));

        ExpenseResponse june1 = createExpense(c.ana(), c.id(), expense("100.00", LocalDate.of(2026, 6, 1), null));
        ExpenseResponse june30 = createExpense(c.ana(), c.id(), expense("100.00", LocalDate.of(2026, 6, 30), null));
        ExpenseResponse july1 = createExpense(c.ana(), c.id(), expense("100.00", LocalDate.of(2026, 7, 1), null));

        assertThat(shares(june1)).containsOnly(Map.entry(c.ana().id(), "75.00"), Map.entry(c.luis().id(), "25.00"));
        assertThat(shares(june30)).isEqualTo(shares(june1));
        assertThat(shares(july1)).containsOnly(Map.entry(c.ana().id(), "0.00"), Map.entry(c.luis().id(), "100.00"));
    }

    @Test
    void leapDayIncomeCountsInFebruaryAndARecurringOneFromThe29thCountsInNonLeapFebruaries() {
        Couple c = couple();
        oneOffIncome(c.ana(), c.id(), "2000.00", LocalDate.of(2028, 2, 29));
        recurring(c.luis(), c.id(), "1000.00", LocalDate.of(2028, 2, 29), null);

        ExpenseResponse feb2028 = createExpense(c.ana(), c.id(), expense("30.00", LocalDate.of(2028, 2, 1), null));
        ExpenseResponse leapDay = createExpense(c.ana(), c.id(), expense("30.00", LocalDate.of(2028, 2, 29), null));
        ExpenseResponse feb2029 = createExpense(c.ana(), c.id(), expense("30.00", LocalDate.of(2029, 2, 28), null));
        ExpenseResponse jan2028 = createExpense(c.ana(), c.id(), expense("30.00", LocalDate.of(2028, 1, 31), null));

        assertThat(shares(feb2028)).containsOnly(Map.entry(c.ana().id(), "20.00"), Map.entry(c.luis().id(), "10.00"));
        assertThat(shares(leapDay)).isEqualTo(shares(feb2028));
        assertThat(shares(feb2029)).containsOnly(Map.entry(c.ana().id(), "0.00"), Map.entry(c.luis().id(), "30.00"));
        // Enero 2028: nadie tiene ingresos → EQUAL con warning.
        assertThat(jan2028.warnings()).containsExactly("PROPORTIONAL_FALLBACK_NO_INCOME");
        assertThat(shares(jan2028)).containsOnly(Map.entry(c.ana().id(), "15.00"), Map.entry(c.luis().id(), "15.00"));
    }

    @Test
    void recurringIncomeEndingOrStartingMidMonthCountsForTheWholeMonth() {
        Couple c = couple();
        recurring(c.ana(), c.id(), "1000.00", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 15));
        recurring(c.luis(), c.id(), "1000.00", LocalDate.of(2026, 3, 20), null);

        ExpenseResponse march1 = createExpense(c.ana(), c.id(), expense("10.00", LocalDate.of(2026, 3, 1), null));
        ExpenseResponse march31 = createExpense(c.ana(), c.id(), expense("10.00", LocalDate.of(2026, 3, 31), null));
        ExpenseResponse april1 = createExpense(c.ana(), c.id(), expense("10.00", LocalDate.of(2026, 4, 1), null));
        ExpenseResponse feb = createExpense(c.ana(), c.id(), expense("10.00", LocalDate.of(2026, 2, 28), null));

        assertThat(shares(march1)).containsOnly(Map.entry(c.ana().id(), "5.00"), Map.entry(c.luis().id(), "5.00"));
        assertThat(march1.warnings()).isEmpty();
        assertThat(shares(march31)).isEqualTo(shares(march1));
        assertThat(shares(april1)).containsOnly(Map.entry(c.ana().id(), "0.00"), Map.entry(c.luis().id(), "10.00"));
        assertThat(shares(feb)).containsOnly(Map.entry(c.ana().id(), "10.00"), Map.entry(c.luis().id(), "0.00"));
    }

    @Test
    void movingTheDateToAnotherMonthRecalculatesWithThatMonthsIncomes() {
        Couple c = couple();
        oneOffIncome(c.ana(), c.id(), "1000.00", LocalDate.of(2026, 8, 31));
        oneOffIncome(c.luis(), c.id(), "1000.00", LocalDate.of(2026, 9, 1));
        ExpenseResponse e = createExpense(c.luis(), c.id(), expense("40.00", LocalDate.of(2026, 8, 31), null));
        assertThat(shares(e)).containsEntry(c.ana().id(), "40.00");

        ResponseEntity<String> moved = call(HttpMethod.PUT, expensePath(c.id(), e.id()), c.luis(),
                expense("40.00", LocalDate.of(2026, 9, 1), null));

        assertThat(moved.getStatusCode()).as(moved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(shares(read(moved, ExpenseResponse.class))).containsEntry(c.luis().id(), "40.00")
                .containsEntry(c.ana().id(), "0.00");
        assertDbMoneyInvariant(c.id());
    }

    @Test
    void incomesOfAnExMemberAndOfTheSameUserInAnotherHouseholdDoNotCount() {
        Couple c = couple();
        TestUser eva = users().create("Eva");
        join(c.ana(), c.id(), eva);
        LocalDate date = LocalDate.of(2026, 10, 15);
        oneOffIncome(c.ana(), c.id(), "1000.00", date);
        oneOffIncome(c.luis(), c.id(), "3000.00", date);
        oneOffIncome(eva, c.id(), "1000000.00", date);
        assertThat(call(HttpMethod.POST, householdPath(c.id()) + "/leave", eva, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Ana tiene además un hogar propio con un ingreso enorme ese mes.
        UUID other = createHousehold(c.ana(), SplitPolicy.PROPORTIONAL);
        oneOffIncome(c.ana(), other, "999999999.99", date);

        ExpenseResponse e = createExpense(c.luis(), c.id(), expense("100.00", date, null));

        assertThat(shares(e)).containsOnly(Map.entry(c.ana().id(), "25.00"), Map.entry(c.luis().id(), "75.00"));
        assertThat(weights(e)).containsOnly(Map.entry(c.ana().id(), "1000.00"), Map.entry(c.luis().id(), "3000.00"));

        // El resumen mensual aplica la misma regla.
        ResponseEntity<String> summary = get(incomesPath(c.id()) + "/summary?month=2026-10", c.luis());
        assertThat(summary.getStatusCode()).as(summary.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = tree(summary);
        assertThat(body.path("total").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(body.path("members").findValuesAsText("userId"))
                .containsExactlyInAnyOrder(c.ana().id().toString(), c.luis().id().toString());

        // Y el gasto del otro hogar solo usa el ingreso de ese hogar.
        ExpenseResponse alone = createExpense(c.ana(), other, expense("10.00", date, null));
        assertThat(weights(alone)).containsOnly(Map.entry(c.ana().id(), "999999999.99"));
    }
}
