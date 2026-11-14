package com.hogar.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hogar.expense.FinanceApiSupport;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.shared.web.PageResponse;
import com.hogar.support.TestUsers.TestUser;
import com.hogar.task.domain.TaskRecurrence;
import com.hogar.task.dto.CompleteTaskRequest;
import com.hogar.task.dto.TaskCompletionResponse;
import com.hogar.task.dto.TaskRequest;
import com.hogar.task.dto.TaskResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Atajos HTTP para los IT de tareas (reutiliza los de hogares y miembros de {@link FinanceApiSupport}).
 * El reloj de la aplicación es el real (UTC): las fechas se construyen relativas a {@link #today()}.
 */
abstract class TaskApiSupport extends FinanceApiSupport {

    @Autowired
    private Clock clock;

    /**
     * Las {@link TaskRequest} se envían como mapa con solo sus componentes: serializar el record
     * invocaría sus métodos {@code @AssertTrue} ({@code isRotationValid} hace {@code contains(null)},
     * que lanza NPE con listas inmutables de {@code List.of}) y añadiría propiedades que no son del contrato.
     */
    @Override
    protected ResponseEntity<String> call(HttpMethod method, String path, TestUser user, Object body) {
        return super.call(method, path, user, body instanceof TaskRequest r ? asJson(r) : body);
    }

    private static Map<String, Object> asJson(TaskRequest r) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", r.title());
        json.put("description", r.description());
        json.put("assigneeId", r.assigneeId());
        json.put("recurrence", r.recurrence());
        json.put("dueDate", r.dueDate() == null ? null : r.dueDate().toString());
        json.put("rotation", r.rotation());
        return json;
    }

    protected LocalDate today() {
        return LocalDate.now(clock);
    }

    protected UUID household(TestUser owner, TestUser... members) {
        UUID id = createHousehold(owner, SplitPolicy.EQUAL);
        join(owner, id, members);
        return id;
    }

    protected void leave(TestUser user, UUID householdId) {
        ResponseEntity<String> response = call(HttpMethod.POST, householdPath(householdId) + "/leave", user, null);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    protected void expel(TestUser owner, UUID householdId, TestUser member) {
        ResponseEntity<String> response = call(HttpMethod.DELETE,
                householdPath(householdId) + "/members/" + member.id(), owner, null);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    protected static TaskRequest oneOff(String title, UUID assignee, LocalDate due) {
        return new TaskRequest(title, null, assignee, TaskRecurrence.NONE, due, null);
    }

    protected static TaskRequest recurring(TaskRecurrence recurrence, LocalDate due, UUID assignee,
                                           List<UUID> rotation) {
        return new TaskRequest("Basura", null, assignee, recurrence, due, rotation);
    }

    protected TaskResponse createTask(TestUser user, UUID householdId, TaskRequest request) {
        ResponseEntity<String> response = call(HttpMethod.POST, tasksPath(householdId), user, request);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return read(response, TaskResponse.class);
    }

    protected TaskResponse getTask(TestUser user, UUID householdId, UUID taskId) {
        ResponseEntity<String> response = get(taskPath(householdId, taskId), user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, TaskResponse.class);
    }

    protected ResponseEntity<String> completeCall(TestUser user, UUID householdId, UUID taskId, LocalDate dueOrNull) {
        return call(HttpMethod.POST, taskPath(householdId, taskId) + "/complete", user,
                dueOrNull == null ? null : new CompleteTaskRequest(dueOrNull));
    }

    protected TaskResponse complete(TestUser user, UUID householdId, UUID taskId) {
        ResponseEntity<String> response = completeCall(user, householdId, taskId, null);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, TaskResponse.class);
    }

    protected PageResponse<TaskResponse> listTasks(TestUser user, UUID householdId, String query) {
        ResponseEntity<String> response = get(tasksPath(householdId) + query, user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, new TypeReference<PageResponse<TaskResponse>>() { });
    }

    protected PageResponse<TaskCompletionResponse> listCompletions(TestUser user, UUID householdId, UUID taskId,
                                                                   String query) {
        ResponseEntity<String> response = get(taskPath(householdId, taskId) + "/completions" + query, user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, new TypeReference<PageResponse<TaskCompletionResponse>>() { });
    }

    protected int dbCompletionCount(UUID taskId) {
        return jdbc.queryForObject("select count(*) from task_completions where task_id = ?", Integer.class, taskId);
    }

    protected static String tasksPath(UUID householdId) {
        return householdPath(householdId) + "/tasks";
    }

    protected static String taskPath(UUID householdId, UUID taskId) {
        return tasksPath(householdId) + "/" + taskId;
    }
}
