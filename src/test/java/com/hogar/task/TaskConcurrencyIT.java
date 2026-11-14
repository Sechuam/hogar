package com.hogar.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.support.TestUsers.TestUser;
import com.hogar.task.domain.TaskRecurrence;
import com.hogar.task.dto.TaskResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Dos miembros completan a la vez la misma ocurrencia: la fila bloqueada (D-044) serializa las
 * peticiones, así que una avanza y la otra recibe 409 (nunca 500 ni dos periodos de avance).
 */
class TaskConcurrencyIT extends TaskApiSupport {

    private static final int ROUNDS = 10;

    @Test
    void twoSimultaneousCompletionsOfTheSameOccurrenceGiveOkAndConflict() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        for (int round = 0; round < ROUNDS; round++) {
            LocalDate due = today();
            TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, due, null,
                    List.of(ana.id(), luis.id())));

            List<ResponseEntity<String>> responses = race(
                    () -> completeCall(ana, h, task.id(), due),
                    () -> completeCall(luis, h, task.id(), due));

            assertOneOkOneConflict(responses, "task-occurrence-mismatch");
            TaskResponse after = getTask(ana, h, task.id());
            assertThat(after.dueDate()).isEqualTo(due.plusDays(1));
            assertThat(after.assigneeId()).isEqualTo(luis.id());
            assertThat(dbCompletionCount(task.id())).isEqualTo(1);
        }
    }

    @Test
    void twoSimultaneousCompletionsOfAOneOffTaskGiveOkAndAlreadyDone() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        for (int round = 0; round < ROUNDS; round++) {
            TaskResponse task = createTask(ana, h, oneOff("Puntual", null, null));

            List<ResponseEntity<String>> responses = race(
                    () -> completeCall(ana, h, task.id(), null),
                    () -> completeCall(luis, h, task.id(), null));

            assertOneOkOneConflict(responses, "task-already-done");
            assertThat(dbCompletionCount(task.id())).isEqualTo(1);
        }
    }

    private void assertOneOkOneConflict(List<ResponseEntity<String>> responses, String conflictCode) {
        assertThat(responses).as(responses.toString())
                .extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        responses.stream().filter(r -> r.getStatusCode().equals(HttpStatus.CONFLICT))
                .forEach(r -> assertProblem(r, HttpStatus.CONFLICT, conflictCode));
    }

    /** Lanza las peticiones a la vez (barrera) y devuelve sus respuestas en orden. */
    @SafeVarargs
    private List<ResponseEntity<String>> race(Callable<ResponseEntity<String>>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CyclicBarrier barrier = new CyclicBarrier(calls.length);
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> call : calls) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.call();
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
