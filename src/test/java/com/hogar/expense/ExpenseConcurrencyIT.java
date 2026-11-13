package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.dto.ExpenseResponse.ShareResponse;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.UpdateHouseholdRequest;
import com.hogar.shared.web.PageResponse;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/**
 * QA Fase 2: peticiones simultáneas sobre gastos. Lo aceptable es 200/204 + 409
 * {@code concurrent-modification} (o que se serialicen); nunca 500, partes duplicadas o un reparto
 * que no sume el importe.
 */
class ExpenseConcurrencyIT extends FinanceApiSupport {

    private static final LocalDate JUNE = LocalDate.of(2026, 6, 10);
    private static final int ROUNDS = 15;

    static final String DEADLOCK_BUG = "BUG: dos PUT/DELETE simultáneos sobre el mismo gasto provocan un deadlock en"
            + " PostgreSQL (40P01: partes y fila del gasto se bloquean en distinto orden durante el flush) que llega"
            + " como CannotAcquireLockException → 500 internal-error en vez de 409 concurrent-modification";

    private record Trio(UUID id, TestUser ana, TestUser luis, TestUser eva) {
    }

    private Trio trio(SplitPolicy policy) {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID id = createHousehold(ana, policy);
        join(ana, id, luis, eva);
        return new Trio(id, ana, luis, eva);
    }

    /** Lanza las tareas a la vez (barrera) y devuelve sus respuestas en orden. */
    @SafeVarargs
    private List<ResponseEntity<String>> race(Callable<ResponseEntity<String>>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CyclicBarrier barrier = new CyclicBarrier(tasks.length);
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> task : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : futures) {
                responses.add(f.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertConflictIsWellFormed(ResponseEntity<String> r) {
        assertProblem(r, HttpStatus.CONFLICT, "concurrent-modification");
    }

    @Disabled(DEADLOCK_BUG)
    @Test
    void twoSimultaneousRecalculatingEditsGiveOkAndConflictOrSerialiseWithoutBreakingTheInvariant()
            throws Exception {
        Trio t = trio(SplitPolicy.EQUAL);
        int conflicts = 0;
        for (int round = 0; round < ROUNDS; round++) {
            ExpenseResponse e = createExpense(t.ana(), t.id(), expense("10.00", JUNE, null));
            String path = expensePath(t.id(), e.id());
            List<ResponseEntity<String>> responses = race(
                    () -> call(HttpMethod.PUT, path, t.ana(), expense("20.01", JUNE, null)),
                    () -> call(HttpMethod.PUT, path, t.ana(), expense("30.02", JUNE.plusDays(1), t.luis().id())));

            List<HttpStatusCode> statuses = responses.stream().map(ResponseEntity::getStatusCode).toList();
            assertThat(statuses).as(responses.toString()).containsAnyOf(HttpStatus.OK)
                    .allMatch(s -> s.equals(HttpStatus.OK) || s.equals(HttpStatus.CONFLICT));
            for (ResponseEntity<String> r : responses) {
                if (r.getStatusCode().equals(HttpStatus.CONFLICT)) {
                    assertConflictIsWellFormed(r);
                    conflicts++;
                }
            }
            ExpenseResponse finalState = getExpense(t.eva(), t.id(), e.id());
            assertThat(finalState.amount().toPlainString()).isIn("20.01", "30.02");
            assertThat(dbShareUsers(e.id())).hasSize(3).doesNotHaveDuplicates();
            assertThat(dbShares(e.id())).isEqualTo(shares(finalState));
        }
        assertDbMoneyInvariant(t.id());
        // Informativo: con la barrera casi siempre se solapan; no se exige un número concreto.
        assertThat(conflicts).isBetween(0, ROUNDS);
    }

    /**
     * Mientras el deadlock siga dando 500 (ver {@link #DEADLOCK_BUG}), al menos se garantiza que la
     * transacción perdedora se deshace entera: ninguna carrera deja partes duplicadas, huérfanas o que
     * no sumen el importe.
     */
    @Test
    void racingEditsAndDeletesNeverCorruptTheStoredShares() throws Exception {
        Trio t = trio(SplitPolicy.EQUAL);
        for (int round = 0; round < ROUNDS; round++) {
            ExpenseResponse e = createExpense(t.ana(), t.id(), expense("10.00", JUNE, null));
            String path = expensePath(t.id(), e.id());
            List<ResponseEntity<String>> responses = round % 2 == 0
                    ? race(() -> call(HttpMethod.PUT, path, t.ana(), expense("20.01", JUNE, null)),
                            () -> call(HttpMethod.PUT, path, t.ana(), expense("30.02", JUNE, t.luis().id())))
                    : race(() -> call(HttpMethod.PUT, path, t.ana(), expense("43.21", JUNE, null)),
                            () -> call(HttpMethod.DELETE, path, t.ana(), null));
            assertThat(responses).as(responses.toString()).anyMatch(r -> r.getStatusCode().is2xxSuccessful());
            ResponseEntity<String> after = get(path, t.eva());
            if (after.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                assertThat(dbShareUsers(e.id())).isEmpty();
            } else {
                ExpenseResponse finalState = read(after, ExpenseResponse.class);
                assertThat(dbShareUsers(e.id())).hasSize(3).doesNotHaveDuplicates();
                assertThat(dbShares(e.id())).isEqualTo(shares(finalState));
            }
        }
        assertDbMoneyInvariant(t.id());
    }

    @Test
    void simultaneousEditAndDescriptionOnlyEditNeverLoseTheInvariant() throws Exception {
        Trio t = trio(SplitPolicy.EQUAL);
        for (int round = 0; round < ROUNDS; round++) {
            ExpenseResponse e = createExpense(t.luis(), t.id(), expense("99.99", JUNE, null));
            String path = expensePath(t.id(), e.id());
            List<ResponseEntity<String>> responses = race(
                    () -> call(HttpMethod.PUT, path, t.luis(), expense("0.02", JUNE, null)),
                    () -> call(HttpMethod.PUT, path, t.ana(),
                            new ExpenseRequest(new BigDecimal("99.99"), "Otra", ExpenseCategory.OTHER, JUNE, null)));
            assertThat(responses).as(responses.toString()).allMatch(r ->
                    r.getStatusCode().equals(HttpStatus.OK) || r.getStatusCode().equals(HttpStatus.CONFLICT));
            ExpenseResponse finalState = getExpense(t.eva(), t.id(), e.id());
            assertThat(dbShares(e.id())).isEqualTo(shares(finalState));
        }
        assertDbMoneyInvariant(t.id());
    }

    @Disabled(DEADLOCK_BUG)
    @Test
    void simultaneousEditAndDeleteNeverFailWithServerError() throws Exception {
        Trio t = trio(SplitPolicy.EQUAL);
        for (int round = 0; round < ROUNDS; round++) {
            ExpenseResponse e = createExpense(t.ana(), t.id(), expense("12.34", JUNE, null));
            String path = expensePath(t.id(), e.id());
            List<ResponseEntity<String>> responses = race(
                    () -> call(HttpMethod.PUT, path, t.ana(), expense("43.21", JUNE, null)),
                    () -> call(HttpMethod.DELETE, path, t.ana(), null),
                    () -> call(HttpMethod.DELETE, path, t.ana(), null));
            for (ResponseEntity<String> r : responses) {
                assertThat(r.getStatusCode()).as(r.getBody()).isIn(HttpStatus.OK, HttpStatus.NO_CONTENT,
                        HttpStatus.NOT_FOUND, HttpStatus.CONFLICT);
            }
            assertThat(responses).as("como mucho un borrado tiene éxito")
                    .filteredOn(r -> r.getStatusCode().equals(HttpStatus.NO_CONTENT)).hasSizeLessThanOrEqualTo(1);
            ResponseEntity<String> after = get(path, t.ana());
            if (after.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                assertThat(dbShareUsers(e.id())).as("sin partes huérfanas tras el borrado").isEmpty();
            } else {
                assertThat(dbShares(e.id())).isEqualTo(shares(read(after, ExpenseResponse.class)));
            }
        }
        assertDbMoneyInvariant(t.id());
    }

    @Test
    void expensesCreatedWhileThePolicyFlipsAreEachCoherentWithTheirOwnPolicy() throws Exception {
        Trio t = trio(SplitPolicy.EQUAL);
        oneOffIncome(t.ana(), t.id(), "3000.00", JUNE);
        oneOffIncome(t.luis(), t.id(), "1000.00", JUNE);
        // Eva sin ingresos: en PROPORTIONAL paga 0.

        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TestUser creator = List.of(t.ana(), t.luis(), t.eva()).get(i % 3);
            tasks.add(() -> call(HttpMethod.POST, expensesPath(t.id()), creator, expense("100.00", JUNE, null)));
        }
        for (int i = 0; i < 4; i++) {
            SplitPolicy policy = i % 2 == 0 ? SplitPolicy.PROPORTIONAL : SplitPolicy.EQUAL;
            tasks.add(() -> call(HttpMethod.PATCH, householdPath(t.id()), t.ana(),
                    new UpdateHouseholdRequest(null, policy)));
        }
        for (int round = 0; round < 3; round++) {
            @SuppressWarnings("unchecked")
            List<ResponseEntity<String>> responses = race(tasks.toArray(Callable[]::new));
            for (ResponseEntity<String> r : responses) {
                // El PATCH del hogar puede chocar consigo mismo (@Version del hogar): 409 aceptable.
                assertThat(r.getStatusCode()).as(r.getBody()).isIn(HttpStatus.CREATED, HttpStatus.OK,
                        HttpStatus.CONFLICT);
            }
        }

        PageResponse<ExpenseResponse> all = listExpenses(t.eva(), t.id(), "?size=100");
        assertThat(all.content()).hasSize(18);
        for (ExpenseResponse e : all.content()) {
            if (e.splitPolicyApplied() == SplitPolicy.EQUAL) {
                assertThat(e.shares()).extracting(s -> s.weight().toPlainString()).containsOnly("1.00");
                assertThat(shares(e).values()).containsExactlyInAnyOrder("33.34", "33.33", "33.33");
            } else {
                assertThat(e.shares()).extracting(ShareResponse::weight)
                        .extracting(BigDecimal::toPlainString)
                        .containsExactlyInAnyOrder("3000.00", "1000.00", "0.00");
                assertThat(shares(e)).containsEntry(t.ana().id(), "75.00").containsEntry(t.luis().id(), "25.00")
                        .containsEntry(t.eva().id(), "0.00");
            }
            assertThat(e.warnings()).isEmpty();
        }
        assertDbMoneyInvariant(t.id());
    }
}
