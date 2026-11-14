package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.dto.ExpenseTotals;
import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.service.ExpenseQueries;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link ExpenseQueries#totalsByUser(UUID)} contra BD real: sigue a los shares congelados tras editar,
 * recalcular y borrar gastos, incluye ex-miembros y no mezcla hogares.
 */
class ExpenseTotalsIT extends FinanceApiSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 5);

    @Autowired
    private ExpenseQueries expenseQueries;

    /** {@code userId → "pagado/debido"} con escala 2. */
    private Map<UUID, String> totals(UUID householdId) {
        return expenseQueries.totalsByUser(householdId).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> format(e.getValue())));
    }

    private static String format(ExpenseTotals t) {
        return t.paid().toPlainString() + "/" + t.owed().toPlainString();
    }

    private ExpenseResponse update(TestUser user, UUID householdId, UUID expenseId, ExpenseRequest request) {
        ResponseEntity<String> response = call(HttpMethod.PUT, expensePath(householdId, expenseId), user, request);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, ExpenseResponse.class);
    }

    @Test
    void householdWithoutExpensesHasNoTotals() {
        TestUser ana = users().create("Ana");
        assertThat(expenseQueries.totalsByUser(createHousehold(ana, SplitPolicy.EQUAL))).isEmpty();
    }

    @Test
    void totalsFollowEditedRecalculatedAndDeletedExpenses() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);
        ExpenseResponse e1 = createExpense(ana, h, expense("10.00", DATE, null));   // 5 / 5
        ExpenseResponse e2 = createExpense(bob, h, expense("20.00", DATE, null));   // 10 / 10
        ExpenseResponse e3 = createExpense(ana, h, expense("99.99", DATE, null));
        assertThat(totals(h)).containsOnly(Map.entry(ana.id(), "109.99/65.00"), Map.entry(bob.id(), "20.00/64.99"));

        assertThat(call(HttpMethod.DELETE, expensePath(h, e3.id()), ana, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(totals(h)).containsOnly(Map.entry(ana.id(), "10.00/15.00"), Map.entry(bob.id(), "20.00/15.00"));

        // Carla entra y E1 se recalcula al cambiar el importe: ahora entre tres (10 / 10 / 10).
        join(ana, h, carla);
        update(ana, h, e1.id(), expense("30.00", DATE, ana.id()));
        // Solo la descripción: los shares de E2 no se tocan (sigue entre Ana y Bob).
        update(bob, h, e2.id(), new ExpenseRequest(new BigDecimal("20.00"), "Otra", ExpenseCategory.OTHER, DATE, null));
        assertThat(totals(h)).containsOnly(Map.entry(ana.id(), "30.00/20.00"), Map.entry(bob.id(), "20.00/20.00"),
                Map.entry(carla.id(), "0.00/10.00"));

        // Cambio de pagador: E2 pasa a Ana (se recalcula con los miembros actuales: 6,67 / 6,67 / 6,66).
        update(bob, h, e2.id(), expense("20.00", DATE, ana.id()));
        assertThat(totals(h)).containsOnly(Map.entry(ana.id(), "50.00/16.67"), Map.entry(bob.id(), "0.00/16.67"),
                Map.entry(carla.id(), "0.00/16.66"));
        assertDbMoneyInvariant(h);
        assertPaidEqualsOwed(h);
    }

    @Test
    void formerMembersKeepTheirTotalsAndOtherHouseholdsAreIgnored() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        UUID other = createHousehold(bob, SplitPolicy.EQUAL);
        join(ana, h, bob);
        createExpense(bob, h, expense("8.00", DATE, null));
        createExpense(bob, other, expense("1000.00", DATE, null));
        assertThat(call(HttpMethod.POST, householdPath(h) + "/leave", bob, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(totals(h)).containsOnly(Map.entry(ana.id(), "0.00/4.00"), Map.entry(bob.id(), "8.00/4.00"));
        assertThat(totals(other)).containsOnly(Map.entry(bob.id(), "1000.00/1000.00"));
        assertPaidEqualsOwed(h);
    }

    private void assertPaidEqualsOwed(UUID householdId) {
        List<ExpenseTotals> values = List.copyOf(expenseQueries.totalsByUser(householdId).values());
        BigDecimal paid = values.stream().map(ExpenseTotals::paid).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal owed = values.stream().map(ExpenseTotals::owed).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(paid).isEqualByComparingTo(owed);
    }
}
