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
import com.hogar.task.domain.TaskStatus;
import com.hogar.task.dto.TaskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/**
 * Escrituras simultáneas sobre la misma tarea: la fila bloqueada (D-044) las serializa. Dos
 * compleciones de la misma ocurrencia → una avanza y la otra 409 (nunca dos periodos); el resto de
 * combinaciones (completar/editar/borrar/reabrir) acaban en uno de los resultados de una ejecución
 * en serie, nunca en 500, deadlock ni 409 {@code data-conflict}.
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

    @Test
    void completeAndPutAtTheSameTimeBothSucceedInSomeOrder() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID h = household(ana, luis, eva);
        for (int round = 0; round < ROUNDS; round++) {
            LocalDate due = today();
            TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, due, null,
                    List.of(ana.id(), luis.id())));
            List<UUID> newRotation = List.of(eva.id(), luis.id(), ana.id());
            String title = "Basura " + round;

            List<ResponseEntity<String>> responses = race(
                    () -> completeCall(luis, h, task.id(), null),
                    () -> call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                            new TaskRequest(title, null, null, TaskRecurrence.DAILY, due, newRotation)));

            assertStatuses(responses, HttpStatus.OK, HttpStatus.OK);
            TaskResponse after = getTask(ana, h, task.id());
            assertThat(after.rotation()).containsExactlyElementsOf(newRotation);
            assertThat(after.title()).isEqualTo(title);
            assertThat(dbCompletionCount(task.id())).isEqualTo(1);
            // PUT después: re-ancla en la fecha enviada; PUT antes: la compleción avanza desde ella.
            assertThat(after.dueDate()).isIn(due, due.plusDays(1));
        }
    }

    @Test
    void completeAndDeleteAtTheSameTimeNeverFail() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        for (int round = 0; round < ROUNDS; round++) {
            TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.WEEKLY, today(), null,
                    List.of(ana.id(), luis.id())));

            List<ResponseEntity<String>> responses = race(
                    () -> completeCall(luis, h, task.id(), today()),
                    () -> call(HttpMethod.DELETE, taskPath(h, task.id()), ana, null));

            assertThat(responses.get(1).getStatusCode()).as(responses.toString()).isEqualTo(HttpStatus.NO_CONTENT);
            if (responses.getFirst().getStatusCode() != HttpStatus.OK) {
                assertProblem(responses.getFirst(), HttpStatus.NOT_FOUND, "task-not-found");
            }
            assertProblem(get(taskPath(h, task.id()), ana), HttpStatus.NOT_FOUND, "task-not-found");
            assertThat(dbCompletionCount(task.id())).isZero();
        }
    }

    @Test
    void reopenAndCompleteAtTheSameTimeEndInASerialOutcome() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        for (int round = 0; round < ROUNDS; round++) {
            TaskResponse task = createTask(ana, h, oneOff("Puntual", null, null));
            complete(ana, h, task.id());

            List<ResponseEntity<String>> responses = race(
                    () -> call(HttpMethod.POST, taskPath(h, task.id()) + "/reopen", ana, null),
                    () -> completeCall(luis, h, task.id(), null));

            assertThat(responses.getFirst().getStatusCode()).as(responses.toString()).isEqualTo(HttpStatus.OK);
            TaskStatus status = getTask(ana, h, task.id()).status();
            if (responses.get(1).getStatusCode() == HttpStatus.OK) {
                // Reabrir y luego completar: hecha otra vez y dos entradas en el histórico.
                assertThat(status).isEqualTo(TaskStatus.DONE);
                assertThat(dbCompletionCount(task.id())).isEqualTo(2);
            } else {
                assertProblem(responses.get(1), HttpStatus.CONFLICT, "task-already-done");
                assertThat(status).isEqualTo(TaskStatus.PENDING);
                assertThat(dbCompletionCount(task.id())).isEqualTo(1);
            }
        }
    }

    @Test
    void twoPutsReorderingTheRotationAtTheSameTimeLeaveOneOfThem() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        TestUser pablo = users().create("Pablo");
        UUID h = household(ana, luis, eva, pablo);
        List<UUID> first = List.of(pablo.id(), eva.id(), luis.id(), ana.id());
        List<UUID> second = List.of(luis.id(), ana.id());
        for (int round = 0; round < ROUNDS; round++) {
            TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null,
                    List.of(ana.id(), luis.id(), eva.id(), pablo.id())));

            List<ResponseEntity<String>> responses = race(
                    () -> call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                            recurring(TaskRecurrence.DAILY, today(), null, first)),
                    () -> call(HttpMethod.PUT, taskPath(h, task.id()), luis,
                            recurring(TaskRecurrence.WEEKLY, today(), null, second)));

            assertStatuses(responses, HttpStatus.OK, HttpStatus.OK);
            TaskResponse after = getTask(ana, h, task.id());
            if (after.recurrence() == TaskRecurrence.DAILY) {
                assertThat(after.rotation()).containsExactlyElementsOf(first);
            } else {
                assertThat(after.rotation()).containsExactlyElementsOf(second);
            }
            assertThat(jdbc.queryForObject("select count(*) from task_rotation_members where task_id = ?",
                    Integer.class, task.id())).isEqualTo(after.rotation().size());
        }
    }

    @Test
    void putAndDeleteAtTheSameTimeNeverFail() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        for (int round = 0; round < ROUNDS; round++) {
            TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null,
                    List.of(ana.id(), luis.id())));

            List<ResponseEntity<String>> responses = race(
                    () -> call(HttpMethod.PUT, taskPath(h, task.id()), luis,
                            recurring(TaskRecurrence.DAILY, today(), null, List.of(luis.id(), ana.id()))),
                    () -> call(HttpMethod.DELETE, taskPath(h, task.id()), ana, null));

            assertThat(responses.get(1).getStatusCode()).as(responses.toString()).isEqualTo(HttpStatus.NO_CONTENT);
            if (responses.getFirst().getStatusCode() != HttpStatus.OK) {
                assertProblem(responses.getFirst(), HttpStatus.NOT_FOUND, "task-not-found");
            }
            assertThat(jdbc.queryForObject("select count(*) from task_rotation_members where task_id = ?",
                    Integer.class, task.id())).isZero();
        }
    }

    private static void assertStatuses(List<ResponseEntity<String>> responses, HttpStatus... expected) {
        assertThat(responses).as(responses.toString())
                .extracting(ResponseEntity::getStatusCode)
                .containsExactly((HttpStatusCode[]) expected);
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
