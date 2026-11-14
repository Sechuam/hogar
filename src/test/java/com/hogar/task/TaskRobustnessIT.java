package com.hogar.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.support.TestUsers.TestUser;
import com.hogar.task.domain.TaskRecurrence;
import com.hogar.task.domain.TaskStatus;
import com.hogar.task.dto.TaskRequest;
import com.hogar.task.dto.TaskResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * QA Fase 3: entradas hostiles, fechas extremas y transiciones de PUT en tareas. Lo que se exige es
 * el código del contrato o, como mínimo, un 4xx con ProblemDetail: nunca 500 ni filtrar detalles internos.
 */
class TaskRobustnessIT extends TaskApiSupport {

    private ResponseEntity<String> raw(HttpMethod method, String path, TestUser user, String body,
                                       MediaType contentTypeOrNull) {
        HttpHeaders headers = user.bearer();
        if (contentTypeOrNull != null) {
            headers.setContentType(contentTypeOrNull);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postJson(String path, TestUser user, String body) {
        return raw(HttpMethod.POST, path, user, body, MediaType.APPLICATION_JSON);
    }

    private void assertClientError(ResponseEntity<String> r) {
        assertThat(r.getStatusCode().is4xxClientError()).as(r.getStatusCode() + " " + r.getBody()).isTrue();
        assertThat(tree(r).path("code").asText()).as(r.getBody()).isNotBlank();
        assertThat(r.getBody()).doesNotContainIgnoringCase("exception").doesNotContainIgnoringCase("org.");
    }

    private static String taskJson(String title, String recurrence, String dueDate) {
        return """
                {"title": %s, "recurrence": %s, "dueDate": %s}
                """.formatted(title, recurrence, dueDate);
    }

    // --- Fechas ---

    @Test
    void hostileDueDatesAreValidationErrorsOfTheField() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        for (String date : new String[] {"\"2026-02-30\"", "\"2026-13-01\"", "\"01/02/2026\"", "\"2026-1-1\"",
                "\"\"", "\"hoy\"", "true", "{}", "[]", "20260101"}) {
            ResponseEntity<String> r = postJson(tasksPath(h), ana, taskJson("\"x\"", "\"DAILY\"", date));
            assertClientError(r);
            assertThat(r.getStatusCode()).as(date + " " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(listTasks(ana, h, "").totalElements()).isZero();
    }

    @Test
    void dueDatesOutsideYears1To9999AreValidationErrors() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        // Antes: los años de 5+ cifras daban 409 data-conflict (DATE fuera de rango en PostgreSQL) y las
        // fechas a.C. se guardaban pero se leían mal (101 a.C. → año 101) o no se podían leer (29-feb del
        // año 0 → 500 en GET, listado y DELETE de esa tarea, para todo el hogar).
        for (String date : new String[] {"\"+999999999-12-31\"", "\"+5874897-12-31\"", "\"+10000-01-01\"",
                "\"-999999999-01-01\"", "\"-0100-03-31\"", "\"0000-02-29\"", "\"0000-12-31\""}) {
            for (String recurrence : new String[] {"\"NONE\"", "\"DAILY\"", "\"MONTHLY\""}) {
                // Lo rechaza ya la deserialización global de fechas (D-047), como error del campo.
                assertValidationError(postJson(tasksPath(h), ana, taskJson("\"x\"", recurrence, date)),
                        "dueDate");
            }
        }
        TaskResponse task = createTask(ana, h, oneOff("Fregar", null, today()));
        assertValidationError(raw(HttpMethod.PUT, taskPath(h, task.id()), ana,
                taskJson("\"x\"", "\"WEEKLY\"", "\"0000-02-29\""), MediaType.APPLICATION_JSON), "dueDate");
        assertThat(listTasks(ana, h, "").content()).singleElement()
                .satisfies(t -> assertThat(t.dueDate()).isEqualTo(today()));
    }

    @Test
    void theFirstAndLastAcceptedDatesAreStoredReadAndAdvancedExactly() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        for (String recurrence : new String[] {"\"DAILY\"", "\"MONTHLY\""}) {
            ResponseEntity<String> created = postJson(tasksPath(h), ana,
                    taskJson("\"x\"", recurrence, "\"0001-01-01\""));
            assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
            TaskResponse task = read(created, TaskResponse.class);
            assertThat(getTask(ana, h, task.id()).dueDate()).isEqualTo(LocalDate.of(1, 1, 1));
            TaskResponse next = complete(ana, h, task.id());
            assertThat(next.dueDate()).isAfter(today());
            assertThat(next.overdue()).isFalse();
        }
        assertThat(listTasks(ana, h, "?overdue=false").totalElements()).isEqualTo(2);
    }

    @Test
    void completingPastYear9999IsRejectedAndLeavesTheTaskUntouched() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, LocalDate.of(9999, 12, 31), null,
                null));

        ResponseEntity<String> r = raw(HttpMethod.POST, taskPath(h, task.id()) + "/complete", ana, null,
                MediaType.APPLICATION_JSON);

        assertProblem(r, HttpStatus.UNPROCESSABLE_ENTITY, "task-schedule-exhausted");
        assertThat(getTask(ana, h, task.id()).dueDate()).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(getTask(ana, h, task.id()).lastCompletedAt()).isNull();
    }

    // --- Texto ---

    @Test
    void hostileTextIsAValidationErrorOfTheField() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        assertValidationError(postJson(tasksPath(h), ana, taskJson("\"a\\u0000b\"", "\"NONE\"", "null")), "title");
        assertValidationError(postJson(tasksPath(h), ana,
                "{\"title\": \"x\", \"description\": \"a\\u0000b\", \"recurrence\": \"NONE\"}"), "description");
        assertValidationError(postJson(tasksPath(h), ana, taskJson("\"   \"", "\"NONE\"", "null")), "title");
        assertValidationError(postJson(tasksPath(h), ana, taskJson("\"\\t\\n\"", "\"NONE\"", "null")), "title");
        assertValidationError(postJson(tasksPath(h), ana, taskJson("null", "\"NONE\"", "null")), "title");
        assertValidationError(postJson(tasksPath(h), ana,
                taskJson("\"" + "x".repeat(101) + "\"", "\"NONE\"", "null")), "title");
        assertValidationError(postJson(tasksPath(h), ana,
                "{\"title\": \"x\", \"description\": \"" + "d".repeat(1001) + "\", \"recurrence\": \"NONE\"}"),
                "description");
        assertValidationError(postJson(tasksPath(h), ana, taskJson("\"x\"", "null", "null")), "recurrence");
        assertValidationError(postJson(tasksPath(h), ana, taskJson("\"x\"", "\"daily\"", "null")), "recurrence");
        assertValidationError(postJson(tasksPath(h), ana, taskJson("[\"x\"]", "\"NONE\"", "null")), "title");
        assertValidationError(postJson(tasksPath(h), ana,
                "{\"title\": \"x\", \"recurrence\": \"NONE\", \"assigneeId\": \"nope\"}"), "assigneeId");
        assertValidationError(postJson(tasksPath(h), ana, "{\"title\": \"x\", \"recurrence\": \"DAILY\","
                + " \"dueDate\": \"2026-03-01\", \"rotation\": \"" + ana.id() + "\"}"), "rotation");
        assertThat(listTasks(ana, h, "").totalElements()).isZero();
    }

    @Test
    void unicodeSpacesOnlyTitleIsAClientErrorNotAServerError() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        // U+2003 (em space) y compañía: @NotBlank (trim()) no los veía blancos, String.isBlank() de la entidad sí → 500.
        for (String title : new String[] {"\"\\u2003\"", "\"\\u2003\\u3000\"", "\"\\u001c\""}) {
            ResponseEntity<String> r = postJson(tasksPath(h), ana, taskJson(title, "\"NONE\"", "null"));
            assertThat(r.getStatusCode()).as(title + " " + r.getBody()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertValidationError(r, "title");
        }
        TaskResponse task = createTask(ana, h, oneOff("Fregar", null, null));
        ResponseEntity<String> put = raw(HttpMethod.PUT, taskPath(h, task.id()), ana,
                taskJson("\"\\u2003\"", "\"NONE\"", "null"), MediaType.APPLICATION_JSON);
        assertValidationError(put, "title");
        assertThat(getTask(ana, h, task.id()).title()).isEqualTo("Fregar");
    }

    @Test
    void textLimitsAreInclusiveAndBlankDescriptionIsNull() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse full = createTask(ana, h,
                new TaskRequest("t".repeat(100), "d".repeat(1000), null, TaskRecurrence.NONE, null, null));
        assertThat(full.title()).hasSize(100);
        assertThat(full.description()).hasSize(1000);
        TaskResponse blankDescription = createTask(ana, h,
                new TaskRequest("x", " \u2003 ", null, TaskRecurrence.NONE, null, null));
        assertThat(blankDescription.description()).isNull();
        // 100 emojis = 200 unidades UTF-16: Bean Validation lo rechaza (400), nunca un error de BD.
        assertClientError(postJson(tasksPath(h), ana, taskJson("\"" + "😀".repeat(100) + "\"", "\"NONE\"", "null")));
        TaskResponse emojis = createTask(ana, h, oneOff("😀".repeat(50), null, null));
        assertThat(emojis.title()).isEqualTo("😀".repeat(50));
    }

    @Test
    void unknownFieldsAreIgnoredAndServerOwnedFieldsCannotBeForced() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        ResponseEntity<String> unknown = postJson(tasksPath(h), ana,
                "{\"title\": \"x\", \"recurrence\": \"NONE\", \"status\": \"DONE\", \"id\": \"" + UUID.randomUUID()
                        + "\", \"createdBy\": \"" + UUID.randomUUID() + "\", \"version\": 7}");
        assertThat(unknown.getStatusCode()).as(unknown.getBody()).isEqualTo(HttpStatus.CREATED);
        TaskResponse task = read(unknown, TaskResponse.class);
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.createdBy()).isEqualTo(ana.id());
    }

    @Test
    void numericRecurrenceIsAValidationError() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        assertValidationError(postJson(tasksPath(h), ana, taskJson("\"x\"", "1", "\"2026-03-01\"")), "recurrence");
    }

    // --- Cuerpo de complete ---

    @Test
    void completeBodyIsOptionalAndMalformedBodiesAreBadRequests() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null, null));
        String path = taskPath(h, task.id()) + "/complete";

        // Sin cuerpo ni Content-Type, cuerpo vacío con JSON, {} y dueDate null: completa la actual.
        ResponseEntity<String> none = raw(HttpMethod.POST, path, ana, null, null);
        assertThat(none.getStatusCode()).as(none.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> empty = raw(HttpMethod.POST, path, ana, "", MediaType.APPLICATION_JSON);
        assertThat(empty.getStatusCode()).as(empty.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> emptyObject = raw(HttpMethod.POST, path, ana, "{}", MediaType.APPLICATION_JSON);
        assertThat(emptyObject.getStatusCode()).as(emptyObject.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> nullDue = raw(HttpMethod.POST, path, ana, "{\"dueDate\": null}",
                MediaType.APPLICATION_JSON);
        assertThat(nullDue.getStatusCode()).as(nullDue.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(dbCompletionCount(task.id())).isEqualTo(4);
        assertThat(getTask(ana, h, task.id()).dueDate()).isEqualTo(today().plusDays(4));

        // Malformados: 4xx y no se completa nada.
        for (String body : new String[] {"{", "[]", "\"2026-01-01\"", "{\"dueDate\": \"ayer\"}",
                "{\"dueDate\": 20260101}", "null"}) {
            ResponseEntity<String> r = raw(HttpMethod.POST, path, ana, body, MediaType.APPLICATION_JSON);
            if (!"null".equals(body)) {
                assertThat(r.getStatusCode()).as(body + " " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertClientError(r);
            } else {
                // JSON "null" es un cuerpo ausente: completa la actual.
                assertThat(r.getStatusCode()).as(body + " " + r.getBody()).isEqualTo(HttpStatus.OK);
            }
        }
        ResponseEntity<String> plain = raw(HttpMethod.POST, path, ana, "{}", MediaType.TEXT_PLAIN);
        assertThat(plain.getStatusCode()).as(plain.getBody()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertClientError(plain);
        assertThat(dbCompletionCount(task.id())).isEqualTo(5);
    }

    // --- Transiciones de PUT ---

    @Test
    void putFromOneOffToWeeklyAnchorsAtTheNewDueDate() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        TaskResponse task = createTask(ana, h, oneOff("Baño", null, null));
        LocalDate due = today().plusDays(3);

        ResponseEntity<String> r = call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                recurring(TaskRecurrence.WEEKLY, due, null, List.of(luis.id(), ana.id())));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        TaskResponse weekly = read(r, TaskResponse.class);
        assertThat(weekly.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(weekly.assigneeId()).isEqualTo(luis.id());
        assertThat(dbAnchor(task.id())).isEqualTo(due);

        TaskResponse next = complete(ana, h, task.id());
        assertThat(next.dueDate()).isEqualTo(due.plusWeeks(1));
        assertThat(next.assigneeId()).isEqualTo(ana.id());
    }

    @Test
    void putFromRecurringToOneOffNeedsToDropTheRotation() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.WEEKLY, today(), null,
                List.of(ana.id(), luis.id())));
        complete(ana, h, task.id());

        // Con la rotación en el cuerpo: 400 y la tarea no cambia.
        assertValidationError(call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                new TaskRequest("Basura", null, null, TaskRecurrence.NONE, today(), List.of(ana.id(), luis.id()))),
                "rotationValid");
        assertThat(getTask(ana, h, task.id()).recurrence()).isEqualTo(TaskRecurrence.WEEKLY);

        // Sin rotación y sin fecha: puntual sin ancla; se puede completar y queda DONE.
        ResponseEntity<String> r = call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                new TaskRequest("Basura", null, luis.id(), TaskRecurrence.NONE, null, List.of()));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        TaskResponse oneOff = read(r, TaskResponse.class);
        assertThat(oneOff.rotation()).isEmpty();
        assertThat(oneOff.dueDate()).isNull();
        assertThat(oneOff.assigneeId()).isEqualTo(luis.id());
        assertThat(dbAnchor(task.id())).isNull();
        assertThat(complete(ana, h, task.id()).status()).isEqualTo(TaskStatus.DONE);

        // Reabierta y vuelta a diaria sin fecha: 400.
        call(HttpMethod.POST, taskPath(h, task.id()) + "/reopen", ana, null);
        assertValidationError(call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                recurring(TaskRecurrence.DAILY, null, null, null)), "dueDateValid");
    }

    @Test
    void putWithoutScheduleChangesKeepsTheAnchorOfAMonthlyTask() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        // Primer 31 futuro seguido de un mes más corto.
        java.time.YearMonth month = java.time.YearMonth.from(today()).plusMonths(1);
        while (month.lengthOfMonth() != 31 || month.plusMonths(1).lengthOfMonth() == 31) {
            month = month.plusMonths(1);
        }
        LocalDate the31st = month.atDay(31);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.MONTHLY, the31st, null, null));
        LocalDate shortMonthEnd = complete(ana, h, task.id()).dueDate();
        assertThat(shortMonthEnd.getDayOfMonth()).isLessThan(31);

        // Solo cambia el título (misma fecha y periodicidad): el ancla sigue siendo el 31.
        ResponseEntity<String> r = call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                new TaskRequest("Otro título", "desc", null, TaskRecurrence.MONTHLY, shortMonthEnd, null));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(dbAnchor(task.id())).isEqualTo(the31st);
        assertThat(complete(ana, h, task.id()).dueDate()).isEqualTo(the31st.plusMonths(2));

        // Cambiar a semanal y volver a mensual con la misma fecha re-ancla en ella (contrato).
        LocalDate current = getTask(ana, h, task.id()).dueDate();
        call(HttpMethod.PUT, taskPath(h, task.id()), ana, recurring(TaskRecurrence.WEEKLY, current, null, null));
        call(HttpMethod.PUT, taskPath(h, task.id()), ana, recurring(TaskRecurrence.MONTHLY, current, null, null));
        assertThat(dbAnchor(task.id())).isEqualTo(current);
    }

    @Test
    void putMovingTheDueDateBeforeTheAnchorReanchorsWithoutViolatingTheDatabase() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today().plusDays(10), null, null));
        complete(ana, h, task.id());

        LocalDate past = today().minusYears(3);
        ResponseEntity<String> r = call(HttpMethod.PUT, taskPath(h, task.id()), ana,
                recurring(TaskRecurrence.DAILY, past, null, null));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(read(r, TaskResponse.class).overdue()).isTrue();
        assertThat(dbAnchor(task.id())).isEqualTo(past);
        // Completar tarde salta a la primera ocurrencia futura.
        assertThat(complete(ana, h, task.id()).dueDate()).isEqualTo(today().plusDays(1));
    }

    // --- Rotación y membresía ---

    @Test
    void rotationOfTwentyIsAcceptedAndTwentyOneIsAValidationError() {
        TestUser owner = users().create("Owner");
        List<TestUser> members = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            members.add(users().create("M" + i));
        }
        UUID h = household(owner, members.toArray(TestUser[]::new));
        List<UUID> twenty = members.stream().map(TestUser::id).toList();
        List<UUID> twentyOne = new ArrayList<>(twenty);
        twentyOne.add(owner.id());

        TaskResponse task = createTask(owner, h, recurring(TaskRecurrence.DAILY, today(), null, twenty));
        assertThat(task.rotation()).containsExactlyElementsOf(twenty);
        assertValidationError(call(HttpMethod.POST, tasksPath(h), owner,
                recurring(TaskRecurrence.DAILY, today(), null, twentyOne)), "rotation");
        assertValidationError(call(HttpMethod.PUT, taskPath(h, task.id()), owner,
                recurring(TaskRecurrence.DAILY, today(), null, twentyOne)), "rotation");

        // Invertir la rotación entera (intercambia usuarios entre posiciones): UNIQUE diferido, sin 409.
        List<UUID> reversed = new ArrayList<>(twenty).reversed();
        ResponseEntity<String> r = call(HttpMethod.PUT, taskPath(h, task.id()), owner,
                recurring(TaskRecurrence.DAILY, today(), null, reversed));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(getTask(owner, h, task.id()).rotation()).containsExactlyElementsOf(reversed);
        // Encoger a 2 desde 20 y volver a crecer.
        r = call(HttpMethod.PUT, taskPath(h, task.id()), owner,
                recurring(TaskRecurrence.DAILY, today(), null, List.of(twenty.get(5), twenty.get(0))));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(getTask(owner, h, task.id()).rotation()).containsExactly(twenty.get(5), twenty.get(0));
        r = call(HttpMethod.PUT, taskPath(h, task.id()), owner,
                recurring(TaskRecurrence.DAILY, today(), null, twenty));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(getTask(owner, h, task.id()).rotation()).containsExactlyElementsOf(twenty);
        assertThat(jdbc.queryForObject("select count(*) from task_rotation_members where task_id = ?",
                Integer.class, task.id())).isEqualTo(20);
    }

    @Test
    void rotationWithASingleActiveMemberKeepsAssigningItAndAReturningMemberRejoinsTheTurn() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), luis.id(),
                List.of(ana.id(), luis.id())));
        leave(luis, h);

        assertThat(complete(ana, h, task.id()).assigneeId()).isEqualTo(ana.id());
        assertThat(complete(ana, h, task.id()).assigneeId()).isEqualTo(ana.id());

        // Luis vuelve al hogar: la rotación (que lo conservaba) vuelve a contar con él.
        join(ana, h, luis);
        assertThat(complete(luis, h, task.id()).assigneeId()).isEqualTo(luis.id());
        assertThat(complete(luis, h, task.id()).assigneeId()).isEqualTo(ana.id());
    }

    @Test
    void anExMemberAssigneeIsKeptOnCompletionWithoutRotationButCannotBeReassignedByPut() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        TaskResponse weekly = createTask(ana, h, recurring(TaskRecurrence.WEEKLY, today(), luis.id(), null));
        TaskResponse oneOff = createTask(ana, h, oneOff("Puntual", luis.id(), today()));
        leave(luis, h);

        TaskResponse next = complete(ana, h, weekly.id());
        assertThat(next.assigneeId()).isEqualTo(luis.id());
        assertThat(complete(ana, h, oneOff.id()).status()).isEqualTo(TaskStatus.DONE);
        assertThat(listCompletions(ana, h, weekly.id(), "").content().getFirst().assigneeId()).isEqualTo(luis.id());
        // El ex-miembro ya no puede completar ni ver tareas del hogar.
        assertProblem(completeCall(luis, h, weekly.id(), null), HttpStatus.NOT_FOUND, "household-not-found");

        assertProblem(call(HttpMethod.PUT, taskPath(h, weekly.id()), ana,
                recurring(TaskRecurrence.WEEKLY, next.dueDate(), luis.id(), null)),
                HttpStatus.UNPROCESSABLE_ENTITY, "assignee-not-member");
        ResponseEntity<String> unassigned = call(HttpMethod.PUT, taskPath(h, weekly.id()), ana,
                recurring(TaskRecurrence.WEEKLY, next.dueDate(), null, null));
        assertThat(unassigned.getStatusCode()).as(unassigned.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(read(unassigned, TaskResponse.class).assigneeId()).isNull();
    }

    // --- Aislamiento ---

    @Test
    void peopleAndFiltersOfAnotherHouseholdNeverLeak() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser marta = users().create("Marta");
        UUID mine = household(ana, luis);
        UUID other = household(marta);
        createTask(marta, other, oneOff("De Marta", marta.id(), null));
        TaskResponse own = createTask(ana, mine, oneOff("Mía", ana.id(), null));

        assertProblem(call(HttpMethod.POST, tasksPath(mine), ana, oneOff("x", marta.id(), null)),
                HttpStatus.UNPROCESSABLE_ENTITY, "assignee-not-member");
        assertProblem(call(HttpMethod.POST, tasksPath(mine), ana,
                recurring(TaskRecurrence.DAILY, today(), null, List.of(ana.id(), marta.id()))),
                HttpStatus.UNPROCESSABLE_ENTITY, "rotation-member-not-member");
        assertProblem(call(HttpMethod.PUT, taskPath(mine, own.id()), ana, oneOff("x", marta.id(), null)),
                HttpStatus.UNPROCESSABLE_ENTITY, "assignee-not-member");
        assertProblem(call(HttpMethod.POST, tasksPath(mine), ana, oneOff("x", UUID.randomUUID(), null)),
                HttpStatus.UNPROCESSABLE_ENTITY, "assignee-not-member");
        assertThat(listTasks(ana, mine, "?assigneeId=" + marta.id()).content()).isEmpty();
        assertThat(listTasks(ana, mine, "?assigneeId=" + other).content()).isEmpty();

        // Sus propias rutas sobre el hogar ajeno: 404 de hogar en todas.
        UUID random = UUID.randomUUID();
        for (ResponseEntity<String> r : List.of(
                get(tasksPath(other), ana),
                call(HttpMethod.POST, tasksPath(other), ana, oneOff("x", null, null)),
                get(taskPath(other, random), ana),
                call(HttpMethod.PUT, taskPath(other, random), ana, oneOff("x", null, null)),
                call(HttpMethod.DELETE, taskPath(other, random), ana, null),
                completeCall(ana, other, random, null),
                call(HttpMethod.POST, taskPath(other, random) + "/reopen", ana, null),
                get(taskPath(other, random) + "/completions", ana))) {
            assertProblem(r, HttpStatus.NOT_FOUND, "household-not-found");
        }
        // Y una tarea inexistente en el propio hogar: 404 de tarea.
        assertProblem(completeCall(ana, mine, random, null), HttpStatus.NOT_FOUND, "task-not-found");
        assertProblem(get(taskPath(mine, "nope"), ana), HttpStatus.BAD_REQUEST, "bad-request");
    }

    private static String taskPath(UUID householdId, String rawTaskId) {
        return tasksPath(householdId) + "/" + rawTaskId;
    }

    // --- Paginación extrema ---

    @Test
    void hugePageNumbersAreBadRequestsNeverServerErrors() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null, null));
        complete(ana, h, task.id());
        String completions = taskPath(h, task.id()) + "/completions";
        int maxPage = Integer.MAX_VALUE / 100;

        // page·size > Integer.MAX_VALUE hacía fallar a Spring Data ("Page offset exceeds…") → 500.
        for (String path : new String[] {
                tasksPath(h) + "?page=2147483647&size=100",
                tasksPath(h) + "?page=21474837&size=100",
                tasksPath(h) + "?page=" + (maxPage + 1) + "&size=1",
                tasksPath(h) + "?page=2147483648",
                completions + "?page=2147483647&size=100",
                completions + "?page=" + (maxPage + 1),
                tasksPath(h) + "?size=" + Long.MAX_VALUE,
                tasksPath(h) + "?overdue=maybe",
                tasksPath(h) + "?mine=maybe"}) {
            ResponseEntity<String> r = get(path, ana);
            assertThat(r.getStatusCode()).as(path + " → " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertClientError(r);
        }
        // La última página admitida es simplemente una página vacía.
        assertThat(listTasks(ana, h, "?page=" + maxPage + "&size=100").content()).isEmpty();
        assertThat(listCompletions(ana, h, task.id(), "?page=" + maxPage + "&size=100").content()).isEmpty();
    }

    private LocalDate dbAnchor(UUID taskId) {
        return jdbc.queryForObject("select anchor_date from tasks where id = ?", LocalDate.class, taskId);
    }
}
