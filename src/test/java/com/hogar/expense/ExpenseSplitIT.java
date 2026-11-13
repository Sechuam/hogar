package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.dto.ExpenseResponse.ShareResponse;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.income.dto.IncomeRequest;
import com.hogar.income.dto.IncomeResponse;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Reparto de punta a punta: HTTP → servicio → SplitCalculator → BD, y snapshot del histórico. */
class ExpenseSplitIT extends FinanceApiSupport {

    private static final LocalDate MARCH = LocalDate.of(2026, 3, 10);

    @Test
    void oldExpensesKeepTheirSharesWhenIncomesOrPolicyChange() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis);
        IncomeResponse anaIncome = oneOffIncome(ana, id, "3000.00", MARCH.withDayOfMonth(1));
        oneOffIncome(luis, id, "1000.00", MARCH.withDayOfMonth(1));

        ExpenseResponse old = createExpense(ana, id, expense("100.00", MARCH, null));
        assertThat(shares(old)).containsOnly(Map.entry(ana.id(), "75.00"), Map.entry(luis.id(), "25.00"));
        assertThat(old.splitPolicyApplied()).isEqualTo(SplitPolicy.PROPORTIONAL);

        // 1) Cambian los ingresos: el gasto antiguo no se toca; uno nuevo usa los ingresos nuevos.
        ResponseEntity<String> edited = call(HttpMethod.PUT, incomePath(id, anaIncome.id()), ana,
                new IncomeRequest(new BigDecimal("1000.00"), "Nómina", MARCH.withDayOfMonth(1), false, null));
        assertThat(edited.getStatusCode()).as(edited.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(getExpense(luis, id, old.id())).usingRecursiveComparison().isEqualTo(old);
        ExpenseResponse afterIncomeChange = createExpense(ana, id, expense("100.00", MARCH, null));
        assertThat(shares(afterIncomeChange))
                .containsOnly(Map.entry(ana.id(), "50.00"), Map.entry(luis.id(), "50.00"));
        assertThat(afterIncomeChange.shares()).extracting(ShareResponse::weight)
                .containsOnly(new BigDecimal("1000.00"));

        // 2) Cambia la política del hogar: ningún gasto existente se toca; uno nuevo es EQUAL.
        oneOffIncome(ana, id, "8000.00", MARCH.withDayOfMonth(2));
        changePolicy(ana, id, SplitPolicy.EQUAL);

        assertThat(getExpense(ana, id, old.id())).usingRecursiveComparison().isEqualTo(old);
        assertThat(getExpense(ana, id, afterIncomeChange.id())).usingRecursiveComparison()
                .isEqualTo(afterIncomeChange);
        ExpenseResponse afterPolicyChange = createExpense(luis, id, expense("10.01", MARCH, null));
        assertThat(afterPolicyChange.splitPolicyApplied()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(shares(afterPolicyChange)).containsOnly(Map.entry(ana.id(), "5.01"), Map.entry(luis.id(), "5.00"));
        assertThat(afterPolicyChange.shares()).extracting(ShareResponse::weight).containsOnly(new BigDecimal("1.00"));
    }

    @Test
    void coupleSplitsAnOddAmountFiftyFiftyAndTheOldestMemberTakesTheCent() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis);

        ExpenseResponse created = createExpense(luis, id, expense("45.01", MARCH, null));

        assertThat(shares(created)).containsOnly(Map.entry(ana.id(), "22.51"), Map.entry(luis.id(), "22.50"));
        assertThat(created.paidBy()).isEqualTo(luis.id());
        assertThat(created.createdBy()).isEqualTo(luis.id());
        assertThat(created.currency()).isEqualTo("EUR");
        assertThat(created.amount().toPlainString()).isEqualTo("45.01");
        assertThat(created.warnings()).isEmpty();
    }

    @Test
    void threeMembersSplitOneHundredEqually() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis, eva);

        ExpenseResponse created = createExpense(eva, id, expense("100.00", MARCH, ana.id()));

        assertThat(shares(created)).containsOnly(Map.entry(ana.id(), "33.34"), Map.entry(luis.id(), "33.33"),
                Map.entry(eva.id(), "33.33"));
        assertThat(created.paidBy()).isEqualTo(ana.id());
        assertThat(created.createdBy()).isEqualTo(eva.id());
    }

    @Test
    void proportionalMemberWithoutIncomePaysZero() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis, eva);
        oneOffIncome(ana, id, "2000.00", MARCH);
        oneOffIncome(luis, id, "1000.00", MARCH);

        ExpenseResponse created = createExpense(eva, id, expense("100.00", MARCH, null));

        assertThat(created.splitPolicyApplied()).isEqualTo(SplitPolicy.PROPORTIONAL);
        assertThat(shares(created)).containsOnly(Map.entry(ana.id(), "66.67"), Map.entry(luis.id(), "33.33"),
                Map.entry(eva.id(), "0.00"));
        assertThat(created.warnings()).isEmpty();
    }

    @Test
    void proportionalWithoutAnyIncomeFallsBackToEqualAndTheWarningIsPersisted() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis);
        oneOffIncome(ana, id, "2000.00", MARCH.minusMonths(1)); // otro mes: no cuenta

        ExpenseResponse created = createExpense(ana, id, expense("10.00", MARCH, null));

        assertThat(created.splitPolicyApplied()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(created.warnings()).containsExactly("PROPORTIONAL_FALLBACK_NO_INCOME");
        assertThat(shares(created)).containsOnly(Map.entry(ana.id(), "5.00"), Map.entry(luis.id(), "5.00"));
        ExpenseResponse fetched = getExpense(luis, id, created.id());
        assertThat(fetched.warnings()).containsExactly("PROPORTIONAL_FALLBACK_NO_INCOME");
        assertThat(listExpenses(luis, id, "").content().get(0).warnings())
                .containsExactly("PROPORTIONAL_FALLBACK_NO_INCOME");
    }

    @Test
    void recurringIncomeCountsInTheExpenseMonthButAnEndedOneDoesNot() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis);
        createIncome(ana, id, new IncomeRequest(new BigDecimal("1500.00"), "Nómina", LocalDate.of(2026, 1, 1), true,
                null));
        createIncome(ana, id, new IncomeRequest(new BigDecimal("500.00"), "Alquiler cobrado",
                LocalDate.of(2026, 1, 15), true, LocalDate.of(2026, 3, 1)));
        createIncome(luis, id, new IncomeRequest(new BigDecimal("5000.00"), "Contrato terminado",
                LocalDate.of(2025, 6, 1), true, LocalDate.of(2026, 2, 28)));

        ExpenseResponse created = createExpense(luis, id, expense("100.00", MARCH, null));

        assertThat(shares(created)).containsOnly(Map.entry(ana.id(), "100.00"), Map.entry(luis.id(), "0.00"));
        assertThat(created.shares()).filteredOn(s -> s.userId().equals(ana.id()))
                .extracting(ShareResponse::weight).containsExactly(new BigDecimal("2000.00"));
        assertThat(created.shares()).filteredOn(s -> s.userId().equals(luis.id()))
                .extracting(ShareResponse::weight).containsExactly(new BigDecimal("0.00"));
    }

    @Test
    void singleMemberPaysEverything() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        oneOffIncome(ana, id, "1.00", MARCH);

        ExpenseResponse created = createExpense(ana, id, expense("0.01", MARCH, null));

        assertThat(shares(created)).containsOnly(Map.entry(ana.id(), "0.01"));
    }

    @Test
    void participantsAreTheActiveMembersWhenTheExpenseIsRegistered() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis, eva);
        assertThat(call(HttpMethod.POST, householdPath(id) + "/leave", eva, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ExpenseResponse created = createExpense(ana, id, expense("10.00", MARCH, null));

        assertThat(shares(created)).containsOnly(Map.entry(ana.id(), "5.00"), Map.entry(luis.id(), "5.00"));
        assertProblem(call(HttpMethod.POST, expensesPath(id), ana, expense("10.00", MARCH, eva.id())),
                HttpStatus.UNPROCESSABLE_ENTITY, "payer-not-member");
    }
}
