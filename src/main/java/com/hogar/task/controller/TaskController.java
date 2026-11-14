package com.hogar.task.controller;

import com.hogar.shared.config.ApiDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = ApiDoc.TAG_TASKS)
class TaskController {

    /**
     * Página máxima: con {@code size} ≤ 100 el desplazamiento ({@code page·size}) cabe en un {@code int},
     * que es lo que admite JPA ({@code setFirstResult}); más allá Spring Data lanza una excepción (500).
     * Fuera de rango → 400, como {@code page < 0} (D-043).
     */
    static final int MAX_PAGE = PageResponse.MAX_PAGE;

    private final TaskService tasks;
    private final CurrentUser currentUser;

    TaskController(TaskService tasks, CurrentUser currentUser) {
        this.tasks = tasks;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Crear una tarea",
            description = "Puntual (`NONE`, con o sin fecha) o recurrente (`DAILY`, `WEEKLY`, `MONTHLY`, con `dueDate` "
                    + "obligatoria, que fija también el ancla de la periodicidad). Con `rotation`, la responsabilidad "
                    + "pasa al siguiente miembro activo de la lista al completarla; `assigneeId` ausente = el primero.")
    @ApiResponse(responseCode = "201", description = "Tarea creada.",
            headers = @Header(name = "Location", description = "URL de la tarea creada", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @ApiResponse(responseCode = "422", description = "`assignee-not-member`: `assigneeId` no es miembro activo." + ApiDoc.NL
            + "`rotation-member-not-member`: algún miembro de `rotation` no es miembro activo." + ApiDoc.NL
            + "`assignee-not-in-rotation`: `assigneeId` no está en `rotation`.")
    @PostMapping
    ResponseEntity<TaskResponse> create(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Valid @RequestBody TaskRequest request) {
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
    @Operation(summary = "Listar las tareas del hogar",
            description = "Orden: `dueDate` ascendente (sin fecha al final) y, a igualdad, la más antigua primero. "
                    + "\"Hoy\" y \"vencida\" se calculan en UTC.")
    @ApiResponse(responseCode = "200", description = "Página de tareas.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PAGINATION + ApiDoc.NL
            + "`conflicting-filters`: `mine=true` y `assigneeId` no pueden usarse a la vez.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping
    PageResponse<TaskResponse> list(@Parameter(description = "ID del hogar") @PathVariable UUID householdId,
                                    @Parameter(description = "`true` = solo las asignadas a mí (`false` equivale a no filtrar)") @RequestParam(required = false) Boolean mine,
                                    @Parameter(description = "Solo las asignadas a este usuario") @RequestParam(required = false) UUID assigneeId,
                                    @Parameter(description = "Solo las de este estado") @RequestParam(required = false) TaskStatus status,
                                    @Parameter(description = "`true` = PENDING con `dueDate` anterior a hoy; `false` = el resto") @RequestParam(required = false) Boolean overdue,
                                    @Parameter(description = ApiDoc.PAGE) @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
                                    @Parameter(description = ApiDoc.SIZE) @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return tasks.list(householdId, currentUser.id(), mine, assigneeId, status, overdue, page, size);
    }

    @Operation(summary = "Consultar una tarea")
    @ApiResponse(responseCode = "200", description = "Tarea.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.TASK_NOT_FOUND)
    @GetMapping("/{taskId}")
    TaskResponse get(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID de la tarea") @PathVariable UUID taskId) {
        return tasks.get(householdId, currentUser.id(), taskId);
    }

    @Operation(summary = "Reemplazar una tarea",
            description = "Cualquier miembro puede editarla. Si cambian `dueDate` o `recurrence`, el ancla pasa a ser "
                    + "la nueva `dueDate`. Una lista `rotation` vacía quita la rotación. Una tarea puntual ya hecha "
                    + "hay que reabrirla antes.")
    @ApiResponse(responseCode = "200", description = "Tarea actualizada.")
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.TASK_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = "`task-already-done`: la tarea está hecha; reábrela antes." + ApiDoc.NL
            + ApiDoc.CONCURRENT_MODIFICATION)
    @ApiResponse(responseCode = "422", description = "`assignee-not-member`: `assigneeId` no es miembro activo." + ApiDoc.NL
            + "`rotation-member-not-member`: algún miembro de `rotation` no es miembro activo." + ApiDoc.NL
            + "`assignee-not-in-rotation`: `assigneeId` no está en `rotation`.")
    @PutMapping("/{taskId}")
    TaskResponse update(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID de la tarea") @PathVariable UUID taskId,
                        @Valid @RequestBody TaskRequest request) {
        return tasks.update(householdId, currentUser.id(), taskId, request);
    }

    @Operation(summary = "Borrar una tarea (creador u OWNER)", description = "Borra también su histórico.")
    @ApiResponse(responseCode = "204", description = "Tarea borrada.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "403", description = "`task-delete-forbidden`: solo quien la creó o un OWNER pueden borrarla.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.TASK_NOT_FOUND)
    @DeleteMapping("/{taskId}")
    ResponseEntity<Void> delete(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID de la tarea") @PathVariable UUID taskId) {
        tasks.delete(householdId, currentUser.id(), taskId);
        return ResponseEntity.noContent().build();
    }

    /** Cuerpo opcional: sin él se completa la ocurrencia actual sea cual sea. */
    @Operation(summary = "Completar una tarea",
            description = "Cualquier miembro activo puede completarla (también por otro). Queda en el histórico quién "
                    + "la hizo, quién era el responsable y qué ocurrencia era. Puntual → pasa a DONE. Recurrente → "
                    + "sigue PENDING con la siguiente fecha futura (completar tarde no acumula atrasos) y, si hay "
                    + "rotación, pasa al siguiente miembro activo. El cuerpo es opcional: con `dueDate` se comprueba "
                    + "que se completa la ocurrencia esperada (evita avanzar dos periodos con un doble toque).")
    @ApiResponse(responseCode = "200", description = "Tarea tras completarla.")
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.TASK_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = "`task-occurrence-mismatch`: la `dueDate` enviada ya no es la "
            + "actual (otra persona la completó antes)." + ApiDoc.NL
            + "`task-already-done`: la tarea puntual ya estaba hecha.")
    @ApiResponse(responseCode = "422", description = "`task-schedule-exhausted`: la siguiente ocurrencia pasaría del "
            + "año 9999.")
    @PostMapping("/{taskId}/complete")
    TaskResponse complete(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID de la tarea") @PathVariable UUID taskId,
                          @RequestBody(required = false) CompleteTaskRequest request) {
        return tasks.complete(householdId, currentUser.id(), taskId, request);
    }

    @Operation(summary = "Reabrir una tarea puntual hecha",
            description = "Vuelve a PENDING; el histórico de compleciones se conserva.")
    @ApiResponse(responseCode = "200", description = "Tarea reabierta.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.TASK_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = "`task-not-done`: solo se reabren tareas puntuales (`NONE`) en DONE.")
    @PostMapping("/{taskId}/reopen")
    TaskResponse reopen(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID de la tarea") @PathVariable UUID taskId) {
        return tasks.reopen(householdId, currentUser.id(), taskId);
    }

    @Operation(summary = "Histórico de compleciones de una tarea", description = "Orden: la más reciente primero.")
    @ApiResponse(responseCode = "200", description = "Página del histórico.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PAGINATION)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.TASK_NOT_FOUND)
    @GetMapping("/{taskId}/completions")
    PageResponse<TaskCompletionResponse> completions(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID de la tarea") @PathVariable UUID taskId,
                                                     @Parameter(description = ApiDoc.PAGE) @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
                                                     @Parameter(description = ApiDoc.SIZE) @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return tasks.completions(householdId, currentUser.id(), taskId, page, size);
    }
}
