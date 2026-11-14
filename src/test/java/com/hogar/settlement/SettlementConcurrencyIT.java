package com.hogar.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.SplitPolicy;
import com.hogar.settlement.dto.BalanceResponse;
import com.hogar.settlement.dto.MemberBalance;
import com.hogar.settlement.dto.SettlementResponse;
import com.hogar.settlement.dto.SuggestedTransfersResponse;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * QA Fase 5: balances y liquidaciones con peticiones simultáneas. Nunca 500, nunca Σ ≠ 0 visible y
 * un borrado doble da 204 + 404.
 */
class SettlementConcurrencyIT extends SettlementApiSupport {

    private static final int ROUNDS = 10;

    @Autowired
    private DataSource dataSource;

    private record Trio(UUID id, TestUser ana, TestUser bob, TestUser carla) {
    }

    private Trio trio() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla);
        return new Trio(h, ana, bob, carla);
    }

    /**
     * Los balances leen las sumas de pagado ({@code shared_expenses}) y de partes
     * ({@code expense_shares}) en sentencias distintas. En READ COMMITTED cada sentencia ve su propia
     * foto: si la edición de un gasto se confirma entre ambas, Σ pagado ≠ Σ debido y el cálculo
     * lanzaba {@code IllegalStateException} → 500. Se fuerza esa intercalación de forma determinista:
     * una transacción bloquea {@code expense_shares} con la edición pendiente, la lectura de
     * balances se queda esperando justo en la segunda suma y entonces se confirma la edición.
     */
    @Test
    void balancesAndSuggestionsNeverSeeAnExpenseEditHalfCommitted() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);
        UUID expenseId = createExpense(ana, h, expense("10.00", MARCH_5, null)).id();

        for (String path : List.of(householdPath(h) + "/balances", settlementsPath(h) + "/suggested")) {
            ResponseEntity<String> response = readWhileAnExpenseEditCommits(expenseId, path, ana);

            assertThat(response.getStatusCode()).as(path + " → " + response.getBody()).isEqualTo(HttpStatus.OK);
            if (path.endsWith("/balances")) {
                BalanceResponse balances = read(response, BalanceResponse.class);
                assertThat(balances.members().stream().map(MemberBalance::balance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
            } else {
                assertThat(read(response, SuggestedTransfersResponse.class).transfers()).hasSize(1);
            }
        }
        // Después de las dos ediciones (×2 y ×2), la foto nueva es coherente: 40 € a medias.
        assertThat(byUser(balances(bob, h))).containsOnly(Map.entry(ana.id(), "20.00"), Map.entry(bob.id(), "-20.00"));
    }

    /** Dobla importe y partes del gasto en una transacción que se confirma con la lectura ya bloqueada. */
    private ResponseEntity<String> readWhileAnExpenseEditCommits(UUID expenseId, String path, TestUser reader)
            throws Exception {
        try (Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            CompletableFuture<ResponseEntity<String>> pending;
            try (Statement st = tx.createStatement()) {
                st.execute("LOCK TABLE expense_shares IN ACCESS EXCLUSIVE MODE");
                st.executeUpdate("UPDATE shared_expenses SET amount = amount * 2 WHERE id = '" + expenseId + "'");
                st.executeUpdate("UPDATE expense_shares SET amount = amount * 2 WHERE expense_id = '" + expenseId + "'");
                pending = CompletableFuture.supplyAsync(() -> get(path, reader));
                awaitBlockedOn("expense_shares", pending);
            } catch (Exception | AssertionError e) {
                tx.rollback();
                throw e;
            }
            tx.commit();
            return pending.get(30, TimeUnit.SECONDS);
        }
    }

    private void awaitBlockedOn(String table, CompletableFuture<?> request) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "select count(*) from pg_locks where relation = ?::regclass and not granted", Integer.class, table);
            if (waiting != null && waiting > 0) {
                return;
            }
            assertThat(request).as("la petición terminó sin llegar a esperar el bloqueo").isNotDone();
            Thread.sleep(10);
        }
        throw new AssertionError("la petición nunca llegó a esperar el bloqueo de " + table);
    }

    /** Escrituras de gastos y liquidaciones mientras otros leen balances y sugerencias: siempre 200 y Σ == 0. */
    @Test
    void readsDuringConcurrentWritesAreAlwaysConsistent() throws Exception {
        Trio t = trio();
        createExpense(t.ana(), t.id(), expense("30.00", MARCH_5, null));
        AtomicBoolean writing = new AtomicBoolean(true);
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<?> settlementsWriter = pool.submit(() -> {
                for (int i = 0; i < 25; i++) {
                    SettlementResponse s = settle(t.bob(), t.id(), t.bob().id(), t.ana().id(), "1.11");
                    deleteSettlement(t.bob(), t.id(), s.id());
                }
            });
            Future<?> expensesWriter = pool.submit(() -> {
                for (int i = 0; i < 15; i++) {
                    UUID id = createExpense(t.carla(), t.id(), expense("7.01", MARCH_5, null)).id();
                    ResponseEntity<String> edited = call(HttpMethod.PUT, expensePath(t.id(), id), t.carla(),
                            expense("9.98", MARCH_5, null));
                    assertThat(edited.getStatusCode()).as(edited.getBody()).isEqualTo(HttpStatus.OK);
                }
            });
            List<Future<?>> readers = new ArrayList<>();
            for (String path : List.of(householdPath(t.id()) + "/balances", settlementsPath(t.id()) + "/suggested")) {
                readers.add(pool.submit(() -> {
                    while (writing.get()) {
                        ResponseEntity<String> r = get(path, t.carla());
                        if (r.getStatusCode() != HttpStatus.OK) {
                            failures.add(path + " → " + r.getStatusCode() + " " + r.getBody());
                        } else if (path.endsWith("/balances")) {
                            BigDecimal sum = read(r, BalanceResponse.class).members().stream()
                                    .map(MemberBalance::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
                            if (sum.signum() != 0) {
                                failures.add("Σ balances = " + sum);
                            }
                        }
                    }
                }));
            }
            try {
                settlementsWriter.get(120, TimeUnit.SECONDS);
                expensesWriter.get(120, TimeUnit.SECONDS);
            } finally {
                writing.set(false);
            }
            for (Future<?> reader : readers) {
                reader.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).isEmpty();
        BalanceResponse after = balances(t.ana(), t.id());
        assertMatchesDb(t.id(), after);
    }

    /** Dos borrados simultáneos de la misma liquidación: uno 204 y el otro 404, nunca 409 ni 500. */
    @Test
    void doubleDeleteOfTheSameSettlementGivesNoContentAndNotFound() throws Exception {
        Trio t = trio();
        for (int round = 0; round < ROUNDS; round++) {
            SettlementResponse s = settle(t.bob(), t.id(), t.bob().id(), t.ana().id(), "5.00");

            List<ResponseEntity<String>> responses = race(
                    () -> deleteSettlement(t.bob(), t.id(), s.id()),
                    () -> deleteSettlement(t.ana(), t.id(), s.id()));

            List<HttpStatus> statuses = responses.stream().map(r -> HttpStatus.valueOf(r.getStatusCode().value()))
                    .toList();
            assertThat(statuses).as("ronda %d: %s", round, responses)
                    .containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.NOT_FOUND);
            responses.stream().filter(r -> r.getStatusCode() == HttpStatus.NOT_FOUND)
                    .forEach(r -> assertProblem(r, HttpStatus.NOT_FOUND, "settlement-not-found"));
        }
        assertThat(listSettlements(t.ana(), t.id(), "").totalElements()).isZero();
    }

    /** Liquidación y gasto creados a la vez: ambos 201 y los balances cuadran con la BD. */
    @Test
    void settlementAndExpenseCreatedAtTheSameTimeBothCount() throws Exception {
        Trio t = trio();
        for (int round = 0; round < ROUNDS; round++) {
            List<ResponseEntity<String>> responses = race(
                    () -> settleCall(t.bob(), t.id(), t.bob().id(), t.ana().id(), "2.00"),
                    () -> call(HttpMethod.POST, expensesPath(t.id()), t.carla(), expense("3.00", MARCH_5, null)),
                    () -> settleCall(t.ana(), t.id(), t.carla().id(), t.bob().id(), "0.50"));
            assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).as(r.getBody())
                    .isEqualTo(HttpStatus.CREATED));
        }
        BalanceResponse balances = balances(t.carla(), t.id());
        assertThat(byUser(balances)).containsOnly(
                Map.entry(t.ana().id(), "-30.00"),   // −10 de partes − 20 recibidos
                Map.entry(t.bob().id(), "5.00"),     // −10 de partes + 20 enviados − 5 recibidos
                Map.entry(t.carla().id(), "25.00")); // 30 pagado − 10 de partes + 5 enviados
        assertMatchesDb(t.id(), balances);
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
}
