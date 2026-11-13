package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.UpdateMemberRoleRequest;
import com.hogar.shared.web.PageResponse;
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

/** CRUD de gastos: alta, consulta, listado con filtros, edición, permisos, aislamiento y validación. */
class ExpenseIT extends FinanceApiSupport {

    private static final LocalDate MARCH = LocalDate.of(2026, 3, 10);

    /** Hogar EQUAL de Ana (OWNER) con Luis y Eva (MEMBER). */
    private record Flat(UUID id, TestUser ana, TestUser luis, TestUser eva) {
    }

    private Flat flat(SplitPolicy policy) {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID id = createHousehold(ana, policy);
        join(ana, id, luis, eva);
        return new Flat(id, ana, luis, eva);
    }

    @Test
    void createReturnsLocationAndTheExpenseCanBeFetched() {
        Flat f = flat(SplitPolicy.EQUAL);

        ResponseEntity<String> response = call(HttpMethod.POST, expensesPath(f.id()), f.luis(),
                new ExpenseRequest(new BigDecimal("30"), "  Luz de marzo  ", ExpenseCategory.ELECTRICITY, MARCH,
                        null));

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        ExpenseResponse created = read(response, ExpenseResponse.class);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath()).isEqualTo(expensePath(f.id(), created.id()));
        assertThat(created.description()).isEqualTo("Luz de marzo");
        assertThat(created.amount().toPlainString()).isEqualTo("30.00");
        assertThat(created.category()).isEqualTo(ExpenseCategory.ELECTRICITY);
        assertThat(created.date()).isEqualTo(MARCH);
        assertThat(shares(created)).containsOnly(Map.entry(f.ana().id(), "10.00"), Map.entry(f.luis().id(), "10.00"),
                Map.entry(f.eva().id(), "10.00"));
        // Instantes con microsegundos en BD: se comparan aparte.
        assertThat(getExpense(f.eva(), f.id(), created.id())).usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt").isEqualTo(created);
    }

    @Test
    void payerMustBeAnActiveMember() {
        Flat f = flat(SplitPolicy.EQUAL);
        TestUser stranger = users().create("Extraño");

        assertProblem(call(HttpMethod.POST, expensesPath(f.id()), f.ana(), expense("10", MARCH, stranger.id())),
                HttpStatus.UNPROCESSABLE_ENTITY, "payer-not-member");
        assertThat(listExpenses(f.ana(), f.id(), "").totalElements()).isZero();
    }

    @Test
    void changingTheAmountRecalculatesWithCurrentMembersAndIncomes() {
        Flat f = flat(SplitPolicy.PROPORTIONAL);
        oneOffIncome(f.ana(), f.id(), "1000.00", MARCH);
        ExpenseResponse created = createExpense(f.luis(), f.id(), expense("50.00", MARCH, null));
        assertThat(shares(created)).containsOnly(Map.entry(f.ana().id(), "50.00"), Map.entry(f.luis().id(), "0.00"),
                Map.entry(f.eva().id(), "0.00"));
        oneOffIncome(f.eva(), f.id(), "3000.00", MARCH);

        ResponseEntity<String> response = call(HttpMethod.PUT, expensePath(f.id(), created.id()), f.luis(),
                expense("100.00", MARCH, null));

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        ExpenseResponse updated = read(response, ExpenseResponse.class);
        assertThat(updated.amount().toPlainString()).isEqualTo("100.00");
        assertThat(shares(updated)).containsOnly(Map.entry(f.ana().id(), "25.00"), Map.entry(f.luis().id(), "0.00"),
                Map.entry(f.eva().id(), "75.00"));
        assertThat(getExpense(f.ana(), f.id(), created.id())).usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt").isEqualTo(updated);
    }

    @Test
    void changingOnlyDescriptionAndCategoryKeepsTheShares() {
        Flat f = flat(SplitPolicy.PROPORTIONAL);
        oneOffIncome(f.ana(), f.id(), "1000.00", MARCH);
        ExpenseResponse created = createExpense(f.ana(), f.id(), expense("50.00", MARCH, null));
        // Ahora Luis tiene ingresos: si se recalculara, cambiaría el reparto.
        oneOffIncome(f.luis(), f.id(), "1000.00", MARCH);

        ExpenseResponse updated = read(call(HttpMethod.PUT, expensePath(f.id(), created.id()), f.ana(),
                new ExpenseRequest(new BigDecimal("50.00"), "Súper", ExpenseCategory.HOME_SUPPLIES, MARCH, null)),
                ExpenseResponse.class);

        assertThat(updated.description()).isEqualTo("Súper");
        assertThat(updated.category()).isEqualTo(ExpenseCategory.HOME_SUPPLIES);
        assertThat(updated.shares()).isEqualTo(created.shares());
        assertThat(getExpense(f.eva(), f.id(), created.id()).shares()).isEqualTo(created.shares());
    }

    @Test
    void ownerEditingWithoutPaidByKeepsTheCurrentPayerAndShares() {
        Flat f = flat(SplitPolicy.EQUAL);
        ExpenseResponse created = createExpense(f.luis(), f.id(), expense("30.00", MARCH, f.eva().id()));

        // Ana (OWNER) corrige la descripción sin reenviar paidBy: Eva sigue siendo la pagadora (D-042).
        ExpenseResponse updated = read(call(HttpMethod.PUT, expensePath(f.id(), created.id()), f.ana(),
                new ExpenseRequest(new BigDecimal("30.00"), "Luz marzo", ExpenseCategory.ELECTRICITY, MARCH, null)),
                ExpenseResponse.class);

        assertThat(updated.paidBy()).isEqualTo(f.eva().id());
        assertThat(updated.description()).isEqualTo("Luz marzo");
        assertThat(updated.shares()).isEqualTo(created.shares());
    }

    @Test
    void onlyPayerCreatorOrOwnerCanEditOrDelete() {
        Flat f = flat(SplitPolicy.EQUAL);
        // Creado por Luis, pagado por Eva... y Pablo (MEMBER) no es ninguno de los dos.
        TestUser pablo = users().create("Pablo");
        join(f.ana(), f.id(), pablo);
        ExpenseResponse created = createExpense(f.luis(), f.id(), expense("40.00", MARCH, f.eva().id()));
        String path = expensePath(f.id(), created.id());

        assertProblem(call(HttpMethod.PUT, path, pablo, expense("1.00", MARCH, f.eva().id())),
                HttpStatus.FORBIDDEN, "expense-edit-forbidden");
        assertProblem(call(HttpMethod.DELETE, path, pablo, null), HttpStatus.FORBIDDEN, "expense-edit-forbidden");
        assertThat(getExpense(pablo, f.id(), created.id()).amount().toPlainString()).isEqualTo("40.00");

        assertThat(call(HttpMethod.PUT, path, f.eva(), expense("41.00", MARCH, f.eva().id())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT, path, f.luis(), expense("42.00", MARCH, f.eva().id())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> byOwner = call(HttpMethod.PUT, path, f.ana(), expense("44.00", MARCH, f.eva().id()));
        assertThat(byOwner.getStatusCode()).as(byOwner.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(shares(read(byOwner, ExpenseResponse.class))).containsValues("11.00").hasSize(4);

        // Pablo pasa a OWNER: ya puede borrar.
        assertThat(call(HttpMethod.PATCH, householdPath(f.id()) + "/members/" + pablo.id(), f.ana(),
                new UpdateMemberRoleRequest(HouseholdRole.OWNER)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.DELETE, path, pablo, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertProblem(get(path, f.ana()), HttpStatus.NOT_FOUND, "expense-not-found");
    }

    @Test
    void strangerGetsHouseholdNotFoundEverywhere() {
        Flat f = flat(SplitPolicy.EQUAL);
        ExpenseResponse created = createExpense(f.ana(), f.id(), expense("10.00", MARCH, null));
        TestUser stranger = users().create("Extraño");
        String path = expensePath(f.id(), created.id());

        assertProblem(call(HttpMethod.POST, expensesPath(f.id()), stranger, expense("1", MARCH, null)),
                HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(expensesPath(f.id()), stranger), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(path, stranger), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.PUT, path, stranger, expense("1", MARCH, null)), HttpStatus.NOT_FOUND,
                "household-not-found");
        assertProblem(call(HttpMethod.DELETE, path, stranger, null), HttpStatus.NOT_FOUND, "household-not-found");
        assertThat(getExpense(f.ana(), f.id(), created.id()).amount().toPlainString()).isEqualTo("10.00");
    }

    @Test
    void expenseOfAnotherHouseholdIsNotFoundThroughMyHousehold() {
        Flat f = flat(SplitPolicy.EQUAL);
        ExpenseResponse foreign = createExpense(f.ana(), f.id(), expense("10.00", MARCH, null));
        TestUser intruder = users().create("Intrusa");
        UUID mine = createHousehold(intruder, SplitPolicy.EQUAL);
        String path = expensePath(mine, foreign.id());

        assertProblem(get(path, intruder), HttpStatus.NOT_FOUND, "expense-not-found");
        assertProblem(call(HttpMethod.PUT, path, intruder, expense("1", MARCH, null)), HttpStatus.NOT_FOUND,
                "expense-not-found");
        assertProblem(call(HttpMethod.DELETE, path, intruder, null), HttpStatus.NOT_FOUND, "expense-not-found");
        assertThat(listExpenses(intruder, mine, "").content()).isEmpty();
        assertThat(getExpense(f.ana(), f.id(), foreign.id()).amount().toPlainString()).isEqualTo("10.00");
    }

    @Test
    void listFiltersAndPaginatesInDateDescOrder() {
        Flat f = flat(SplitPolicy.EQUAL);
        UUID e1 = createExpense(f.ana(), f.id(), new ExpenseRequest(new BigDecimal("1"), "a",
                ExpenseCategory.RENT, LocalDate.of(2026, 1, 31), null)).id();
        UUID e2 = createExpense(f.luis(), f.id(), new ExpenseRequest(new BigDecimal("2"), "b",
                ExpenseCategory.GROCERIES, LocalDate.of(2026, 2, 1), null)).id();
        UUID e3 = createExpense(f.ana(), f.id(), new ExpenseRequest(new BigDecimal("3"), "c",
                ExpenseCategory.GROCERIES, LocalDate.of(2026, 2, 15), f.eva().id())).id();
        UUID e4 = createExpense(f.eva(), f.id(), new ExpenseRequest(new BigDecimal("4"), "d",
                ExpenseCategory.GROCERIES, LocalDate.of(2026, 2, 15), null)).id();
        UUID e5 = createExpense(f.ana(), f.id(), new ExpenseRequest(new BigDecimal("5"), "e",
                ExpenseCategory.WATER, LocalDate.of(2026, 3, 1), null)).id();
        // Gasto de otro hogar con la misma fecha: nunca aparece.
        TestUser other = users().create("Otro");
        createExpense(other, createHousehold(other, SplitPolicy.EQUAL), expense("9", LocalDate.of(2026, 2, 15), null));

        // Misma fecha → el creado después va primero.
        assertThat(ids(listExpenses(f.luis(), f.id(), ""))).containsExactly(e5, e4, e3, e2, e1);
        assertThat(ids(listExpenses(f.luis(), f.id(), "?from=2026-02-01&to=2026-02-15")))
                .containsExactly(e4, e3, e2);
        assertThat(ids(listExpenses(f.luis(), f.id(), "?from=2026-02-02"))).containsExactly(e5, e4, e3);
        assertThat(ids(listExpenses(f.luis(), f.id(), "?to=2026-01-31"))).containsExactly(e1);
        assertThat(ids(listExpenses(f.luis(), f.id(), "?category=GROCERIES"))).containsExactly(e4, e3, e2);
        assertThat(ids(listExpenses(f.luis(), f.id(), "?paidBy=" + f.eva().id()))).containsExactly(e4, e3);
        assertThat(ids(listExpenses(f.luis(), f.id(), "?category=GROCERIES&paidBy=" + f.eva().id()
                + "&from=2026-02-15&to=2026-02-15"))).containsExactly(e4, e3);

        PageResponse<ExpenseResponse> page1 = listExpenses(f.luis(), f.id(), "?page=1&size=2");
        assertThat(ids(page1)).containsExactly(e3, e2);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.size()).isEqualTo(2);
        assertThat(page1.totalElements()).isEqualTo(5);
        assertThat(page1.totalPages()).isEqualTo(3);
        assertThat(ids(listExpenses(f.luis(), f.id(), "?page=2&size=2"))).containsExactly(e1);
        assertThat(listExpenses(f.luis(), f.id(), "?page=3&size=2").content()).isEmpty();
        assertThat(listExpenses(f.luis(), f.id(), "?size=100").content()).hasSize(5);
        assertThat(listExpenses(f.luis(), f.id(), "").content().get(0).shares()).hasSize(3);
    }

    @Test
    void invalidListParametersAreBadRequest() {
        Flat f = flat(SplitPolicy.EQUAL);
        String path = expensesPath(f.id());

        assertProblem(get(path + "?from=2026-03-02&to=2026-03-01", f.ana()), HttpStatus.BAD_REQUEST,
                "invalid-date-range");
        assertProblem(get(path + "?size=101", f.ana()), HttpStatus.BAD_REQUEST, "bad-request");
        assertProblem(get(path + "?size=0", f.ana()), HttpStatus.BAD_REQUEST, "bad-request");
        assertProblem(get(path + "?page=-1", f.ana()), HttpStatus.BAD_REQUEST, "bad-request");
        assertProblem(get(path + "?category=FOOD", f.ana()), HttpStatus.BAD_REQUEST, "bad-request");
        assertProblem(get(path + "?from=01/03/2026", f.ana()), HttpStatus.BAD_REQUEST, "bad-request");
        assertProblem(get(path + "?paidBy=nadie", f.ana()), HttpStatus.BAD_REQUEST, "bad-request");
    }

    @Test
    void invalidBodiesAreValidationErrors() {
        Flat f = flat(SplitPolicy.EQUAL);
        String path = expensesPath(f.id());

        assertValidationError(call(HttpMethod.POST, path, f.ana(), expense("0", MARCH, null)), "amount");
        assertValidationError(call(HttpMethod.POST, path, f.ana(), expense("0.00", MARCH, null)), "amount");
        assertValidationError(call(HttpMethod.POST, path, f.ana(), expense("-5", MARCH, null)), "amount");
        assertValidationError(call(HttpMethod.POST, path, f.ana(), expense("10.001", MARCH, null)), "amount");
        assertValidationError(call(HttpMethod.POST, path, f.ana(),
                Map.of("amount", 10, "description", "x", "category", "FOOD", "date", "2026-03-01")), "category");
        assertValidationError(call(HttpMethod.POST, path, f.ana(),
                Map.of("amount", 10, "description", " ", "category", "RENT", "date", "2026-03-01")), "description");
        assertValidationError(call(HttpMethod.POST, path, f.ana(),
                Map.of("amount", 10, "description", "x", "category", "RENT")), "date");
        assertValidationError(call(HttpMethod.POST, path, f.ana(),
                Map.of("amount", 10, "description", "x", "category", "RENT", "date", "2026-03-01",
                        "paidBy", "nadie")), "paidBy");
        ExpenseResponse created = createExpense(f.ana(), f.id(), expense("10", MARCH, null));
        assertValidationError(call(HttpMethod.PUT, expensePath(f.id(), created.id()), f.ana(),
                expense("10.001", MARCH, null)), "amount");
        assertThat(listExpenses(f.ana(), f.id(), "").totalElements()).isEqualTo(1);
    }

    private static List<UUID> ids(PageResponse<ExpenseResponse> page) {
        return page.content().stream().map(ExpenseResponse::id).toList();
    }
}
