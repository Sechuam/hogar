package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.income.dto.IncomeRequest;
import com.hogar.income.dto.IncomeResponse;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * QA Fase 2: el reparto de un gasto es una foto (snapshot) que no cambia por nada de lo que pase
 * después en el hogar, hasta que se edita su importe/fecha/pagador: entonces se recalcula con los
 * miembros actuales (D-034) sin dejar partes huérfanas en BD. También permisos de ex-miembros
 * sobre sus ingresos y gastos antiguos (D-042).
 */
class ExpenseSnapshotLifecycleIT extends FinanceApiSupport {

    private static final LocalDate MAY = LocalDate.of(2026, 5, 12);

    private ExpenseResponse withoutTimestamps(ExpenseResponse e) {
        return new ExpenseResponse(e.id(), e.amount(), e.currency(), e.description(), e.category(), e.date(),
                e.paidBy(), e.createdBy(), e.splitPolicyApplied(), e.shares(), e.warnings(), null, null,
                e.hasInvoice());
    }

    private void assertUnchanged(TestUser reader, UUID householdId, ExpenseResponse before) {
        ExpenseResponse now = getExpense(reader, householdId, before.id());
        assertThat(withoutTimestamps(now)).isEqualTo(withoutTimestamps(before));
        assertThat(now.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(dbShares(before.id())).isEqualTo(shares(before));
    }

    private void leave(TestUser user, UUID householdId) {
        ResponseEntity<String> left = call(HttpMethod.POST, householdPath(householdId) + "/leave", user, null);
        assertThat(left.getStatusCode()).as(left.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void expel(TestUser owner, UUID householdId, TestUser user) {
        ResponseEntity<String> removed = call(HttpMethod.DELETE, householdPath(householdId) + "/members/" + user.id(),
                owner, null);
        assertThat(removed.getStatusCode()).as(removed.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void anOldExpenseSurvivesEverythingAndIsRecalculatedOnlyWhenItsAmountChanges() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        TestUser pablo = users().create("Pablo");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis, eva, pablo);
        IncomeResponse anaIncome = oneOffIncome(ana, id, "1000.33", MAY);
        oneOffIncome(luis, id, "2000.01", MAY);
        IncomeResponse evaIncome = oneOffIncome(eva, id, "1500.50", MAY);
        // Pablo: 0 € → paga 0 (D-009).

        // Pagado por Eva y registrado por Luis.
        ExpenseResponse old = getExpense(ana, id,
                createExpense(luis, id, expense("123.45", MAY, eva.id())).id());
        assertThat(old.shares()).hasSize(4);
        assertThat(shares(old)).containsEntry(pablo.id(), "0.00");
        String evaShare = shares(old).get(eva.id());

        // 1) Ingresos: alta, edición y borrado.
        oneOffIncome(pablo, id, "5000.00", MAY);
        assertThat(call(HttpMethod.PUT, incomePath(id, anaIncome.id()), ana,
                new IncomeRequest(new BigDecimal("9.99"), "Nómina", MAY, true, null)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertUnchanged(ana, id, old);

        // 2) Política del hogar.
        changePolicy(ana, id, SplitPolicy.EQUAL);
        assertUnchanged(ana, id, old);
        changePolicy(ana, id, SplitPolicy.PROPORTIONAL);

        // 3) Entra un miembro nuevo.
        TestUser nuevo = users().create("Nuevo");
        join(ana, id, nuevo);
        oneOffIncome(nuevo, id, "700.00", MAY);
        assertUnchanged(nuevo, id, old);

        // 4) Eva (pagadora) borra su ingreso y se va; Luis (creador) es expulsado.
        assertThat(call(HttpMethod.DELETE, incomePath(id, evaIncome.id()), eva, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        oneOffIncome(eva, id, "100000.00", MAY); // ingreso de quien se va a ir: no debe contar después
        leave(eva, id);
        expel(ana, id, luis);
        assertUnchanged(ana, id, old);
        assertThat(shares(old)).containsEntry(eva.id(), evaShare); // la foto conserva a los ex-miembros
        assertDbMoneyInvariant(id);

        // 5) Editar solo la descripción: sigue siendo la foto (y el pagador ex-miembro se conserva, D-042).
        ResponseEntity<String> described = call(HttpMethod.PUT, expensePath(id, old.id()), ana,
                new ExpenseRequest(new BigDecimal("123.45"), "Compra grande", ExpenseCategory.GROCERIES, MAY, null));
        assertThat(described.getStatusCode()).as(described.getBody()).isEqualTo(HttpStatus.OK);
        ExpenseResponse afterDescription = read(described, ExpenseResponse.class);
        assertThat(afterDescription.paidBy()).isEqualTo(eva.id());
        assertThat(afterDescription.shares()).isEqualTo(old.shares());
        assertThat(dbShares(old.id())).isEqualTo(shares(old));

        // 6) Editar el importe: se recalcula con los miembros e ingresos ACTUALES (D-034).
        ResponseEntity<String> recalculated = call(HttpMethod.PUT, expensePath(id, old.id()), ana,
                expense("100.00", MAY, null));
        assertThat(recalculated.getStatusCode()).as(recalculated.getBody()).isEqualTo(HttpStatus.OK);
        ExpenseResponse after = read(recalculated, ExpenseResponse.class);
        assertThat(after.paidBy()).as("el pagador ex-miembro se conserva").isEqualTo(eva.id());
        assertThat(after.splitPolicyApplied()).isEqualTo(SplitPolicy.PROPORTIONAL);
        // Ana 9.99 · Pablo 5000.00 · Nuevo 700.00 → total 5709.99
        assertThat(shares(after)).containsOnlyKeys(ana.id(), pablo.id(), nuevo.id());
        assertThat(after.shares()).extracting(s -> s.weight().toPlainString())
                .containsExactlyInAnyOrder("9.99", "5000.00", "700.00");
        assertThat(dbShareUsers(old.id())).containsExactlyInAnyOrderElementsOf(List.of(ana.id(), pablo.id(),
                nuevo.id()));
        assertThat(dbShares(old.id())).isEqualTo(shares(after));
        assertDbMoneyInvariant(id);
    }

    @Test
    void recalculatingWithTheSameMembersReplacesSharesWithoutDuplicatesOrOrphans() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis);
        ExpenseResponse created = createExpense(ana, id, expense("10.01", MAY, null));
        UUID before = jdbc.queryForObject("select id from expense_shares where expense_id = ? and user_id = ?",
                UUID.class, created.id(), ana.id());

        for (String amount : new String[] {"10.02", "0.03", "77.77", "10.01"}) {
            ResponseEntity<String> r = call(HttpMethod.PUT, expensePath(id, created.id()), ana,
                    expense(amount, MAY.plusDays(1), null));
            assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        }

        assertThat(dbShareUsers(created.id())).containsExactlyInAnyOrder(ana.id(), luis.id());
        assertThat(jdbc.queryForObject("select count(*) from expense_shares where expense_id = ?", Long.class,
                created.id())).isEqualTo(2);
        UUID after = jdbc.queryForObject("select id from expense_shares where expense_id = ? and user_id = ?",
                UUID.class, created.id(), ana.id());
        assertThat(after).as("las partes se sustituyen, no se actualizan").isNotEqualTo(before);
        assertDbMoneyInvariant(id);
    }

    @Test
    void deletingAnExpenseLeavesNoShares() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis);
        ExpenseResponse created = createExpense(luis, id, expense("9.99", MAY, null));

        assertThat(call(HttpMethod.DELETE, expensePath(id, created.id()), luis, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(dbShareUsers(created.id())).isEmpty();
        assertProblem(call(HttpMethod.DELETE, expensePath(id, created.id()), luis, null), HttpStatus.NOT_FOUND,
                "expense-not-found");
    }

    @Test
    void payerWhoLeftCanStillBeKeptByTheOwnerAndCreatorButNotReassignedToAnotherExMember() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        TestUser pablo = users().create("Pablo");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis, eva, pablo);
        ExpenseResponse e = createExpense(luis, id, expense("30.00", MAY, eva.id()));
        leave(eva, id);
        leave(pablo, id);
        String path = expensePath(id, e.id());

        // El creador (MEMBER, sigue en el hogar) puede editar la descripción reenviando el pagador ex-miembro.
        ResponseEntity<String> byCreator = call(HttpMethod.PUT, path, luis,
                new ExpenseRequest(new BigDecimal("30.00"), "Luz", ExpenseCategory.ELECTRICITY, MAY, eva.id()));
        assertThat(byCreator.getStatusCode()).as(byCreator.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(read(byCreator, ExpenseResponse.class).shares()).isEqualTo(e.shares());

        // El OWNER también, sin paidBy.
        ResponseEntity<String> byOwner = call(HttpMethod.PUT, path, ana,
                new ExpenseRequest(new BigDecimal("30.00"), "Luz mayo", ExpenseCategory.ELECTRICITY, MAY, null));
        assertThat(byOwner.getStatusCode()).as(byOwner.getBody()).isEqualTo(HttpStatus.OK);

        // Pero no se puede pasar el pago a otro ex-miembro.
        assertProblem(call(HttpMethod.PUT, path, ana, expense("30.00", MAY, pablo.id())),
                HttpStatus.UNPROCESSABLE_ENTITY, "payer-not-member");
        // Y la propia pagadora ya no ve el hogar.
        assertProblem(call(HttpMethod.PUT, path, eva, expense("30.00", MAY, null)), HttpStatus.NOT_FOUND,
                "household-not-found");
        assertProblem(call(HttpMethod.DELETE, path, eva, null), HttpStatus.NOT_FOUND, "household-not-found");
        assertThat(getExpense(ana, id, e.id()).description()).isEqualTo("Luz mayo");
    }

    @Test
    void exMemberCannotTouchTheirOldIncomeButItStaysVisibleAndDoesNotCount() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis);
        oneOffIncome(ana, id, "1000.00", MAY);
        IncomeResponse luisIncome = oneOffIncome(luis, id, "3000.00", MAY);
        leave(luis, id);
        String path = incomePath(id, luisIncome.id());

        assertProblem(call(HttpMethod.PUT, path, luis,
                new IncomeRequest(BigDecimal.ONE, "x", MAY, false, null)), HttpStatus.NOT_FOUND,
                "household-not-found");
        assertProblem(call(HttpMethod.DELETE, path, luis, null), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(path, luis), HttpStatus.NOT_FOUND, "household-not-found");
        // El OWNER tampoco puede borrarlo: es de Luis.
        assertProblem(call(HttpMethod.DELETE, path, ana, null), HttpStatus.FORBIDDEN, "income-owner-required");

        // Sigue visible en el listado del hogar (transparencia), pero no cuenta en el reparto ni en el resumen.
        assertThat(listIncomes(ana, id, "?month=2026-05").content()).extracting(IncomeResponse::id)
                .contains(luisIncome.id());
        assertThat(tree(get(incomesPath(id) + "/summary?month=2026-05", ana)).path("total").decimalValue())
                .isEqualByComparingTo("1000.00");
        ExpenseResponse e = createExpense(ana, id, expense("50.00", MAY, null));
        assertThat(shares(e)).containsOnly(Map.entry(ana.id(), "50.00"));
        assertThat(e.shares().get(0).weight().toPlainString()).isEqualTo("1000.00");
    }
}
