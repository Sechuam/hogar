package com.hogar.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.hogar.shared.web.PageResponse;
import com.hogar.support.TestUsers.TestUser;
import com.hogar.task.domain.TaskRecurrence;
import com.hogar.task.domain.TaskStatus;
import com.hogar.task.dto.TaskCompletionResponse;
import com.hogar.task.dto.TaskRequest;
import com.hogar.task.dto.TaskResponse;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Tareas de punta a punta: HTTP → servicio → PostgreSQL, con membresía y rotación reales. */
class TaskIT extends TaskApiSupport {

    // --- CRUD ---

    @Test
    void crudOfAOneOffTask() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);

        ResponseEntity<String> created = call(HttpMethod.POST, tasksPath(h), ana,
                new TaskRequest("  Fregar  ", "Platos de la cena", luis.id(), TaskRecurrence.NONE, today(), null));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        TaskResponse task = read(created, TaskResponse.class);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getHeaders().getLocation().getPath()).isEqualTo(taskPath(h, task.id()));
        assertThat(task.title()).isEqualTo("Fregar");
        assertThat(task.assigneeId()).isEqualTo(luis.id());
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.createdBy()).isEqualTo(ana.id());
        assertThat(task.rotation()).isEmpty();
        assertThat(task.overdue()).isFalse();
        assertThat(task.lastCompletedAt()).isNull();

        assertSameTask(getTask(luis, h, task.id()), task);

        ResponseEntity<String> updated = call(HttpMethod.PUT, taskPath(h, task.id()), luis,
                new TaskRequest("Fregar y secar", null, null, TaskRecurrence.NONE, today().minusDays(1), null));
        assertThat(updated.getStatusCode()).as(updated.getBody()).isEqualTo(HttpStatus.OK);
        TaskResponse afterPut = read(updated, TaskResponse.class);
        assertThat(afterPut.title()).isEqualTo("Fregar y secar");
        assertThat(afterPut.description()).isNull();
        assertThat(afterPut.assigneeId()).isNull();
        assertThat(afterPut.overdue()).isTrue();
        assertSameTask(getTask(ana, h, task.id()), afterPut);

        assertThat(call(HttpMethod.DELETE, taskPath(h, task.id()), ana, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertProblem(get(taskPath(h, task.id()), ana), HttpStatus.NOT_FOUND, "task-not-found");
    }

    @Test
    void invalidBodiesAreValidationErrors() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);

        assertValidationError(call(HttpMethod.POST, tasksPath(h), ana, oneOff(" ", null, null)), "title");
        assertValidationError(call(HttpMethod.POST, tasksPath(h), ana,
                recurring(TaskRecurrence.WEEKLY, null, null, null)), "dueDateValid");
        assertValidationError(call(HttpMethod.POST, tasksPath(h), ana,
                new TaskRequest("x", null, null, TaskRecurrence.NONE, null, List.of(ana.id(), luis.id()))),
                "rotationValid");
        assertValidationError(call(HttpMethod.POST, tasksPath(h), ana,
                recurring(TaskRecurrence.DAILY, today(), null, List.of(ana.id(), ana.id()))), "rotationValid");
        assertValidationError(call(HttpMethod.POST, tasksPath(h), ana,
                Map.of("title", "x", "recurrence", "YEARLY")), "recurrence");
        ResponseEntity<String> nullMember = call(HttpMethod.POST, tasksPath(h), ana, Map.of("title", "x",
                "recurrence", "DAILY", "dueDate", today().toString(), "rotation", Arrays.asList(ana.id(), null)));
        assertThat(nullMember.getStatusCode()).as(nullMember.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(listTasks(ana, h, "").totalElements()).isZero();
    }

    // --- Filtros y paginación ---

    @Test
    void filtersByMineAssigneeStatusAndOverdue() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        TaskResponse anaOverdue = createTask(ana, h, oneOff("Vencida de Ana", ana.id(), today().minusDays(2)));
        TaskResponse anaToday = createTask(ana, h, oneOff("Hoy de Ana", ana.id(), today()));
        TaskResponse luisFuture = createTask(ana, h, oneOff("Futura de Luis", luis.id(), today().plusDays(3)));
        TaskResponse noDate = createTask(ana, h, oneOff("Sin fecha", null, null));
        TaskResponse doneOverdue = createTask(ana, h, oneOff("Hecha", luis.id(), today().minusDays(5)));
        complete(luis, h, doneOverdue.id());

        assertThat(ids(listTasks(ana, h, ""))).containsExactly(
                doneOverdue.id(), anaOverdue.id(), anaToday.id(), luisFuture.id(), noDate.id());
        assertThat(ids(listTasks(ana, h, "?mine=true"))).containsExactly(anaOverdue.id(), anaToday.id());
        assertThat(ids(listTasks(luis, h, "?mine=true"))).containsExactly(doneOverdue.id(), luisFuture.id());
        assertThat(ids(listTasks(luis, h, "?mine=false"))).hasSize(5);
        assertThat(ids(listTasks(luis, h, "?assigneeId=" + ana.id()))).containsExactly(anaOverdue.id(), anaToday.id());
        assertThat(ids(listTasks(ana, h, "?status=DONE"))).containsExactly(doneOverdue.id());
        assertThat(ids(listTasks(ana, h, "?status=PENDING&mine=true"))).containsExactly(anaOverdue.id(), anaToday.id());
        assertThat(ids(listTasks(ana, h, "?overdue=true"))).containsExactly(anaOverdue.id());
        assertThat(listTasks(ana, h, "?overdue=true").content().getFirst().overdue()).isTrue();
        assertThat(ids(listTasks(ana, h, "?overdue=false"))).containsExactly(
                doneOverdue.id(), anaToday.id(), luisFuture.id(), noDate.id());
        assertThat(ids(listTasks(luis, h, "?overdue=true&mine=true"))).isEmpty();

        PageResponse<TaskResponse> second = listTasks(ana, h, "?page=1&size=2");
        assertThat(ids(second)).containsExactly(anaToday.id(), luisFuture.id());
        assertThat(second.totalElements()).isEqualTo(5);
        assertThat(second.totalPages()).isEqualTo(3);
    }

    @Test
    void conflictingAndMalformedFiltersAreBadRequests() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);

        assertProblem(get(tasksPath(h) + "?mine=true&assigneeId=" + ana.id(), ana), HttpStatus.BAD_REQUEST,
                "conflicting-filters");
        assertThat(get(tasksPath(h) + "?size=101", ana).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(tasksPath(h) + "?size=0", ana).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(tasksPath(h) + "?page=-1", ana).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(tasksPath(h) + "?status=LATER", ana).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(tasksPath(h) + "?assigneeId=no-uuid", ana).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(tasksPath(h) + "?size=100", ana).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Compleción puntual ---

    @Test
    void oneOffCompletionDoneTwiceConflictsAndCanBeReopened() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        TaskResponse task = createTask(ana, h, oneOff("Tender", ana.id(), today().minusDays(1)));

        // Cualquier miembro puede completar la tarea de otro.
        TaskResponse done = complete(luis, h, task.id());
        assertThat(done.status()).isEqualTo(TaskStatus.DONE);
        assertThat(done.overdue()).isFalse();
        assertThat(done.lastCompletedAt()).isNotNull();

        assertProblem(completeCall(ana, h, task.id(), null), HttpStatus.CONFLICT, "task-already-done");
        assertProblem(call(HttpMethod.PUT, taskPath(h, task.id()), ana, oneOff("Otra", null, null)),
                HttpStatus.CONFLICT, "task-already-done");

        ResponseEntity<String> reopened = call(HttpMethod.POST, taskPath(h, task.id()) + "/reopen", ana, null);
        assertThat(reopened.getStatusCode()).as(reopened.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(read(reopened, TaskResponse.class).status()).isEqualTo(TaskStatus.PENDING);
        assertThat(read(reopened, TaskResponse.class).overdue()).isTrue();
        assertProblem(call(HttpMethod.POST, taskPath(h, task.id()) + "/reopen", ana, null),
                HttpStatus.CONFLICT, "task-not-done");

        // El histórico se conserva al reabrir.
        List<TaskCompletionResponse> history = listCompletions(ana, h, task.id(), "").content();
        assertThat(history).singleElement().satisfies(c -> {
            assertThat(c.completedBy()).isEqualTo(luis.id());
            assertThat(c.assigneeId()).isEqualTo(ana.id());
            assertThat(c.dueDate()).isEqualTo(today().minusDays(1));
            assertThat(c.taskId()).isEqualTo(task.id());
        });
    }

    @Test
    void recurringTasksCannotBeReopened() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null, null));

        assertProblem(call(HttpMethod.POST, taskPath(h, task.id()) + "/reopen", ana, null),
                HttpStatus.CONFLICT, "task-not-done");
    }

    // --- Recurrentes ---

    @Test
    void dailyWeeklyAndMonthlyAdvanceTheirDueDate() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        LocalDate today = today();
        TaskResponse daily = createTask(ana, h, recurring(TaskRecurrence.DAILY, today, ana.id(), null));
        TaskResponse weekly = createTask(ana, h, recurring(TaskRecurrence.WEEKLY, today, ana.id(), null));
        TaskResponse monthly = createTask(ana, h, recurring(TaskRecurrence.MONTHLY, today, ana.id(), null));

        TaskResponse nextDaily = complete(ana, h, daily.id());
        assertThat(nextDaily.dueDate()).isEqualTo(today.plusDays(1));
        assertThat(nextDaily.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(nextDaily.assigneeId()).isEqualTo(ana.id());
        assertThat(nextDaily.lastCompletedAt()).isNotNull();
        assertThat(complete(ana, h, weekly.id()).dueDate()).isEqualTo(today.plusWeeks(1));
        assertThat(complete(ana, h, monthly.id()).dueDate()).isEqualTo(today.plusMonths(1));
        // Completar antes de tiempo avanza un periodo desde la fecha actual.
        assertThat(complete(ana, h, daily.id()).dueDate()).isEqualTo(today.plusDays(2));
    }

    @Test
    void lateCompletionSkipsMissedOccurrences() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse weekly = createTask(ana, h, recurring(TaskRecurrence.WEEKLY, today().minusDays(15), null, null));
        assertThat(weekly.overdue()).isTrue();

        TaskResponse next = complete(ana, h, weekly.id());

        assertThat(next.dueDate()).isEqualTo(today().plusDays(6));
        assertThat(next.overdue()).isFalse();
    }

    @Test
    void monthlyOnThe31stGoesToTheLastDayOfShorterMonthsAndBack() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        // Primer día 31 futuro seguido de un mes más corto (no julio ni diciembre).
        YearMonth month = YearMonth.from(today()).plusMonths(1);
        while (month.lengthOfMonth() != 31 || month.plusMonths(1).lengthOfMonth() == 31) {
            month = month.plusMonths(1);
        }
        LocalDate the31st = month.atDay(31);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.MONTHLY, the31st, null, null));

        LocalDate first = complete(ana, h, task.id()).dueDate();
        assertThat(first).isEqualTo(month.plusMonths(1).atEndOfMonth());
        assertThat(first.getDayOfMonth()).isLessThan(31);
        assertThat(complete(ana, h, task.id()).dueDate()).isEqualTo(the31st.plusMonths(2));
    }

    @Test
    void completingAnotherOccurrenceIsAMismatch() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null, null));

        assertProblem(completeCall(ana, h, task.id(), today().plusDays(1)), HttpStatus.CONFLICT,
                "task-occurrence-mismatch");
        ResponseEntity<String> ok = completeCall(ana, h, task.id(), today());
        assertThat(ok.getStatusCode()).as(ok.getBody()).isEqualTo(HttpStatus.OK);
        // Doble toque con la misma ocurrencia: no avanza dos periodos.
        assertProblem(completeCall(ana, h, task.id(), today()), HttpStatus.CONFLICT, "task-occurrence-mismatch");
        assertThat(getTask(ana, h, task.id()).dueDate()).isEqualTo(today().plusDays(1));
        assertThat(dbCompletionCount(task.id())).isEqualTo(1);
    }

    @Test
    void completionHistoryIsPagedNewestFirst() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null, null));
        complete(ana, h, task.id());
        complete(luis, h, task.id());
        complete(ana, h, task.id());

        PageResponse<TaskCompletionResponse> first = listCompletions(luis, h, task.id(), "?size=2");
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).extracting(TaskCompletionResponse::dueDate)
                .containsExactly(today().plusDays(2), today().plusDays(1));
        assertThat(first.content()).extracting(TaskCompletionResponse::completedBy)
                .containsExactly(ana.id(), luis.id());
        assertThat(listCompletions(luis, h, task.id(), "?page=1&size=2").content())
                .extracting(TaskCompletionResponse::dueDate).containsExactly(today());
        assertThat(get(taskPath(h, task.id()) + "/completions?size=101", ana).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(taskPath(h, task.id()) + "/completions?page=-1", ana).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- Rotación ---

    @Test
    void rotationSkipsMembersWhoLeftOrWereExpelled() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        TestUser pablo = users().create("Pablo");
        UUID h = household(ana, luis, eva, pablo);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.WEEKLY, today(), null,
                List.of(luis.id(), eva.id(), pablo.id())));
        assertThat(task.assigneeId()).isEqualTo(luis.id());

        assertThat(complete(ana, h, task.id()).assigneeId()).isEqualTo(eva.id());

        // Eva abandona el hogar siendo la asignada: su tarea sigue asignada a ella hasta completarla.
        leave(eva, h);
        assertThat(getTask(ana, h, task.id()).assigneeId()).isEqualTo(eva.id());
        TaskResponse afterEva = complete(luis, h, task.id());
        assertThat(afterEva.assigneeId()).isEqualTo(pablo.id());
        assertThat(complete(pablo, h, task.id()).assigneeId()).isEqualTo(luis.id());
        // Luis → (Eva salió) → Pablo.
        assertThat(complete(luis, h, task.id()).assigneeId()).isEqualTo(pablo.id());

        // Pablo es expulsado: Pablo → (Luis) ; Luis → (Eva y Pablo fuera) → Luis.
        expel(ana, h, pablo);
        assertThat(complete(ana, h, task.id()).assigneeId()).isEqualTo(luis.id());
        TaskResponse last = complete(ana, h, task.id());
        assertThat(last.assigneeId()).isEqualTo(luis.id());
        assertThat(last.rotation()).containsExactly(luis.id(), eva.id(), pablo.id());
        assertThat(last.dueDate()).isEqualTo(today().plusWeeks(6));

        assertThat(listCompletions(ana, h, task.id(), "").content())
                .extracting(TaskCompletionResponse::assigneeId)
                .containsExactly(luis.id(), pablo.id(), luis.id(), pablo.id(), eva.id(), luis.id());
    }

    @Test
    void rotationWithNobodyActiveLeavesTheTaskUnassigned() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID h = household(ana, luis, eva);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null,
                List.of(luis.id(), eva.id())));
        leave(luis, h);
        leave(eva, h);

        TaskResponse next = complete(ana, h, task.id());

        assertThat(next.assigneeId()).isNull();
        assertThat(listTasks(ana, h, "?assigneeId=" + luis.id()).content()).isEmpty();
    }

    @Test
    void peopleMustBeActiveMembersAndTheAssigneePartOfTheRotation() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        TestUser stranger = users().create("Extraño");
        TestUser former = users().create("Exmiembro");
        UUID h = household(ana, luis, eva, former);
        leave(former, h);

        assertProblem(call(HttpMethod.POST, tasksPath(h), ana, oneOff("x", stranger.id(), null)),
                HttpStatus.UNPROCESSABLE_ENTITY, "assignee-not-member");
        assertProblem(call(HttpMethod.POST, tasksPath(h), ana, oneOff("x", former.id(), null)),
                HttpStatus.UNPROCESSABLE_ENTITY, "assignee-not-member");
        assertProblem(call(HttpMethod.POST, tasksPath(h), ana,
                recurring(TaskRecurrence.DAILY, today(), null, List.of(luis.id(), stranger.id()))),
                HttpStatus.UNPROCESSABLE_ENTITY, "rotation-member-not-member");
        assertProblem(call(HttpMethod.POST, tasksPath(h), ana,
                recurring(TaskRecurrence.DAILY, today(), former.id(), List.of(luis.id(), former.id()))),
                HttpStatus.UNPROCESSABLE_ENTITY, "assignee-not-member");
        assertProblem(call(HttpMethod.POST, tasksPath(h), ana,
                recurring(TaskRecurrence.DAILY, today(), eva.id(), List.of(ana.id(), luis.id()))),
                HttpStatus.UNPROCESSABLE_ENTITY, "assignee-not-in-rotation");
        assertThat(listTasks(ana, h, "").totalElements()).isZero();

        // PUT aplica las mismas reglas y puede cambiar o quitar la rotación.
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null,
                List.of(ana.id(), luis.id())));
        assertProblem(call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                recurring(TaskRecurrence.DAILY, today(), null, List.of(luis.id(), former.id()))),
                HttpStatus.UNPROCESSABLE_ENTITY, "rotation-member-not-member");
        assertThat(getTask(ana, h, task.id()).rotation()).containsExactly(ana.id(), luis.id());

        ResponseEntity<String> changed = call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                recurring(TaskRecurrence.DAILY, today(), null, List.of(eva.id(), luis.id(), ana.id())));
        assertThat(changed.getStatusCode()).as(changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(read(changed, TaskResponse.class).assigneeId()).isEqualTo(eva.id());
        assertThat(getTask(luis, h, task.id()).rotation()).containsExactly(eva.id(), luis.id(), ana.id());

        ResponseEntity<String> removed = call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                recurring(TaskRecurrence.DAILY, today(), luis.id(), List.of()));
        assertThat(removed.getStatusCode()).as(removed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(getTask(luis, h, task.id()).rotation()).isEmpty();
        assertThat(getTask(luis, h, task.id()).assigneeId()).isEqualTo(luis.id());
    }

    @Test
    void putChangingTheDueDateMovesTheAnchor() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.WEEKLY, today(), null, null));
        LocalDate newDue = today().plusDays(2);

        call(HttpMethod.PUT, taskPath(h, task.id()), ana, recurring(TaskRecurrence.WEEKLY, newDue, null, null));

        assertThat(complete(ana, h, task.id()).dueDate()).isEqualTo(newDue.plusWeeks(1));
    }

    // --- Borrado ---

    @Test
    void onlyTheCreatorOrAnOwnerCanDeleteAndTheHistoryGoesWithTheTask() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID h = household(ana, luis, eva);
        TaskResponse byLuis = createTask(luis, h, oneOff("De Luis", eva.id(), null));
        TaskResponse byLuis2 = createTask(luis, h, recurring(TaskRecurrence.DAILY, today(), null, null));
        complete(eva, h, byLuis2.id());
        complete(eva, h, byLuis2.id());

        // Ni siquiera la asignada puede borrar lo que no creó.
        assertProblem(call(HttpMethod.DELETE, taskPath(h, byLuis.id()), eva, null), HttpStatus.FORBIDDEN,
                "task-delete-forbidden");
        assertThat(getTask(eva, h, byLuis.id()).title()).isEqualTo("De Luis");

        assertThat(call(HttpMethod.DELETE, taskPath(h, byLuis.id()), ana, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbCompletionCount(byLuis2.id())).isEqualTo(2);
        assertThat(call(HttpMethod.DELETE, taskPath(h, byLuis2.id()), luis, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbCompletionCount(byLuis2.id())).isZero();
        assertProblem(call(HttpMethod.DELETE, taskPath(h, byLuis2.id()), luis, null), HttpStatus.NOT_FOUND,
                "task-not-found");
    }

    // --- Aislamiento ---

    @Test
    void aTaskOfAnotherHouseholdIsNotFound() {
        TestUser ana = users().create("Ana");
        TestUser marta = users().create("Marta");
        UUID mine = household(ana);
        UUID other = household(marta);
        TaskResponse foreign = createTask(marta, other, recurring(TaskRecurrence.DAILY, today(), null, null));
        TaskResponse foreignOneOff = createTask(marta, other, oneOff("x", null, null));
        complete(marta, other, foreignOneOff.id());

        assertProblem(get(taskPath(mine, foreign.id()), ana), HttpStatus.NOT_FOUND, "task-not-found");
        assertProblem(call(HttpMethod.PUT, taskPath(mine, foreign.id()), ana, oneOff("Mía", null, null)),
                HttpStatus.NOT_FOUND, "task-not-found");
        assertProblem(completeCall(ana, mine, foreign.id(), null), HttpStatus.NOT_FOUND, "task-not-found");
        assertProblem(call(HttpMethod.POST, taskPath(mine, foreignOneOff.id()) + "/reopen", ana, null),
                HttpStatus.NOT_FOUND, "task-not-found");
        assertProblem(get(taskPath(mine, foreign.id()) + "/completions", ana), HttpStatus.NOT_FOUND,
                "task-not-found");
        assertProblem(call(HttpMethod.DELETE, taskPath(mine, foreign.id()), ana, null), HttpStatus.NOT_FOUND,
                "task-not-found");
        // Y por la ruta de su hogar, Ana no es miembro.
        assertProblem(get(taskPath(other, foreign.id()), ana), HttpStatus.NOT_FOUND, "household-not-found");

        assertThat(listTasks(ana, mine, "").content()).isEmpty();
        assertSameTask(getTask(marta, other, foreign.id()), foreign);
        assertThat(getTask(marta, other, foreignOneOff.id()).status()).isEqualTo(TaskStatus.DONE);
    }

    /** Los instantes se comparan aparte: la BD los guarda con precisión de microsegundos. */
    private static void assertSameTask(TaskResponse actual, TaskResponse expected) {
        assertThat(actual).usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt", "lastCompletedAt").isEqualTo(expected);
        assertThat(actual.updatedAt()).isCloseTo(expected.updatedAt(), within(1, ChronoUnit.MILLIS));
    }

    private static List<UUID> ids(PageResponse<TaskResponse> page) {
        return page.content().stream().map(TaskResponse::id).toList();
    }
}
