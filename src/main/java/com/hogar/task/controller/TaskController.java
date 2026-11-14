package com.hogar.task.controller;

import com.hogar.shared.security.CurrentUser;
import com.hogar.shared.web.PageResponse;
import com.hogar.task.domain.TaskStatus;
import com.hogar.task.dto.CompleteTaskRequest;
import com.hogar.task.dto.TaskCompletionResponse;
import com.hogar.task.dto.TaskRequest;
import com.hogar.task.dto.TaskResponse;
import com.hogar.task.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/households/{householdId}/tasks")
class TaskController {

    /**
     * Página máxima: con {@code size} ≤ 100 el desplazamiento ({@code page·size}) cabe en un {@code int},
     * que es lo que admite JPA ({@code setFirstResult}); más allá Spring Data lanza una excepción (500).
     * Fuera de rango → 400, como {@code page < 0} (D-043).
     */
    static final int MAX_PAGE = Integer.MAX_VALUE / 100;

    private final TaskService tasks;
    private final CurrentUser currentUser;

    TaskController(TaskService tasks, CurrentUser currentUser) {
        this.tasks = tasks;
        this.currentUser = currentUser;
    }

    @PostMapping
    ResponseEntity<TaskResponse> create(@PathVariable UUID householdId, @Valid @RequestBody TaskRequest request) {
        TaskResponse created = tasks.create(householdId, currentUser.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * {@code mine=true} = asignadas a mí (incompatible con {@code assigneeId}); {@code overdue=true} =
     * PENDING con {@code dueDate} anterior a hoy. {@code size} fuera de 1..100 o {@code page} fuera de
     * 0..{@value #MAX_PAGE} → 400.
     */
    @GetMapping
    PageResponse<TaskResponse> list(@PathVariable UUID householdId,
                                    @RequestParam(required = false) Boolean mine,
                                    @RequestParam(required = false) UUID assigneeId,
                                    @RequestParam(required = false) TaskStatus status,
                                    @RequestParam(required = false) Boolean overdue,
                                    @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return tasks.list(householdId, currentUser.id(), mine, assigneeId, status, overdue, page, size);
    }

    @GetMapping("/{taskId}")
    TaskResponse get(@PathVariable UUID householdId, @PathVariable UUID taskId) {
        return tasks.get(householdId, currentUser.id(), taskId);
    }

    @PutMapping("/{taskId}")
    TaskResponse update(@PathVariable UUID householdId, @PathVariable UUID taskId,
                        @Valid @RequestBody TaskRequest request) {
        return tasks.update(householdId, currentUser.id(), taskId, request);
    }

    @DeleteMapping("/{taskId}")
    ResponseEntity<Void> delete(@PathVariable UUID householdId, @PathVariable UUID taskId) {
        tasks.delete(householdId, currentUser.id(), taskId);
        return ResponseEntity.noContent().build();
    }

    /** Cuerpo opcional: sin él se completa la ocurrencia actual sea cual sea. */
    @PostMapping("/{taskId}/complete")
    TaskResponse complete(@PathVariable UUID householdId, @PathVariable UUID taskId,
                          @RequestBody(required = false) CompleteTaskRequest request) {
        return tasks.complete(householdId, currentUser.id(), taskId, request);
    }

    @PostMapping("/{taskId}/reopen")
    TaskResponse reopen(@PathVariable UUID householdId, @PathVariable UUID taskId) {
        return tasks.reopen(householdId, currentUser.id(), taskId);
    }

    @GetMapping("/{taskId}/completions")
    PageResponse<TaskCompletionResponse> completions(@PathVariable UUID householdId, @PathVariable UUID taskId,
                                                     @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return tasks.completions(householdId, currentUser.id(), taskId, page, size);
    }
}
