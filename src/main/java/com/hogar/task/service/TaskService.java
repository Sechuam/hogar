package com.hogar.task.service;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.dto.ActiveMember;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.shared.error.BadRequestException;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.ConflictException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import com.hogar.shared.web.PageResponse;
import com.hogar.task.domain.Task;
import com.hogar.task.domain.TaskStatus;
import com.hogar.task.domain.schedule.RecurrenceSchedule;
import com.hogar.task.domain.schedule.TaskRotation;
import com.hogar.task.dto.CompleteTaskRequest;
import com.hogar.task.dto.TaskCompletionResponse;
import com.hogar.task.dto.TaskRequest;
import com.hogar.task.dto.TaskResponse;
import com.hogar.task.repository.TaskCompletionRepository;
import com.hogar.task.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tareas de casa (contrato Fase 3, §3). Orden en cada operación: membresía primero (D-032) y, en
 * escrituras sobre una tarea existente, la fila bloqueada ({@code findForUpdate}, D-044) antes de
 * comprobar su estado. Las reglas de estado se validan aquí, antes de llamar a la entidad, para
 * devolver los códigos del contrato; la entidad mantiene sus invariantes como última barrera.
 * "Hoy" = {@code LocalDate.now(clock)} (UTC).
 */
@Service
public class TaskService {

    static final String TASK_NOT_FOUND = "task-not-found";
    static final String TASK_ALREADY_DONE = "task-already-done";
    static final String TASK_NOT_DONE = "task-not-done";
    static final String TASK_OCCURRENCE_MISMATCH = "task-occurrence-mismatch";
    static final String TASK_DELETE_FORBIDDEN = "task-delete-forbidden";
    static final String ASSIGNEE_NOT_MEMBER = "assignee-not-member";
    static final String ROTATION_MEMBER_NOT_MEMBER = "rotation-member-not-member";
    static final String ASSIGNEE_NOT_IN_ROTATION = "assignee-not-in-rotation";
    static final String CONFLICTING_FILTERS = "conflicting-filters";
    static final String TASK_SCHEDULE_EXHAUSTED = "task-schedule-exhausted";

    private final TaskRepository tasks;
    private final TaskCompletionRepository completions;
    private final MembershipGuard guard;
    private final HouseholdQueries households;
    private final Clock clock;

    TaskService(TaskRepository tasks, TaskCompletionRepository completions, MembershipGuard guard,
                HouseholdQueries households, Clock clock) {
        this.tasks = tasks;
        this.completions = completions;
        this.guard = guard;
        this.households = households;
        this.clock = clock;
    }

    @Transactional
    public TaskResponse create(UUID householdId, UUID userId, TaskRequest request) {
        guard.requireMember(householdId, userId);
        requireValidPeople(households.getSplitInfo(householdId), request);
        Task task = Task.create(householdId, userId, request.title(), request.description(), request.assigneeId(),
                request.recurrence(), request.dueDate(), request.rotationOrEmpty(), Instant.now(clock));
        return TaskMapper.toResponse(tasks.save(task), today());
    }

    /**
     * Tareas del hogar en orden {@code dueDate asc (sin fecha al final), alta asc}.
     *
     * @param mineOrNull {@code true} = asignadas al usuario; incompatible con {@code assigneeId}
     * @throws BadRequestException {@code conflicting-filters} si llegan {@code mine=true} y {@code assigneeId}
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(UUID householdId, UUID userId, Boolean mineOrNull, UUID assigneeIdOrNull,
                                           TaskStatus statusOrNull, Boolean overdueOrNull, int page, int size) {
        guard.requireMember(householdId, userId);
        boolean mine = Boolean.TRUE.equals(mineOrNull);
        if (mine && assigneeIdOrNull != null) {
            throw new BadRequestException(CONFLICTING_FILTERS, "'mine' y 'assigneeId' no pueden usarse a la vez.");
        }
        UUID assignee = mine ? userId : assigneeIdOrNull;
        LocalDate today = today();
        return PageResponse.of(tasks.search(householdId, assignee, statusOrNull, overdueOrNull, today,
                PageRequest.of(page, size)), task -> TaskMapper.toResponse(task, today));
    }

    @Transactional(readOnly = true)
    public TaskResponse get(UUID householdId, UUID userId, UUID taskId) {
        guard.requireMember(householdId, userId);
        return TaskMapper.toResponse(load(householdId, taskId), today());
    }

    /** PUT: reemplaza los datos. Una tarea DONE hay que reabrirla antes. */
    @Transactional
    public TaskResponse update(UUID householdId, UUID userId, UUID taskId, TaskRequest request) {
        guard.requireMember(householdId, userId);
        Task task = loadForUpdate(householdId, taskId);
        if (task.getStatus() == TaskStatus.DONE) {
            throw new ConflictException(TASK_ALREADY_DONE, "La tarea está hecha: reábrela antes de editarla.");
        }
        requireValidPeople(households.getSplitInfo(householdId), request);
        // Entidad gestionada: el flush al cerrar la transacción sincroniza los cambios.
        task.update(request.title(), request.description(), request.assigneeId(), request.recurrence(),
                request.dueDate(), request.rotationOrEmpty(), Instant.now(clock));
        return TaskMapper.toResponse(task, today());
    }

    /** Solo el creador o un OWNER. El histórico se borra en cascada. */
    @Transactional
    public void delete(UUID householdId, UUID userId, UUID taskId) {
        guard.requireMember(householdId, userId);
        Task task = loadForUpdate(householdId, taskId);
        if (!userId.equals(task.getCreatedBy()) && !isOwner(households.getSplitInfo(householdId), userId)) {
            throw new ForbiddenException(TASK_DELETE_FORBIDDEN,
                    "Solo quien creó la tarea o un OWNER puede borrarla.");
        }
        tasks.delete(task);
    }

    /**
     * Completa la ocurrencia actual (cualquier miembro activo puede hacerlo). Puntual → DONE.
     * Recurrente → siguiente {@code dueDate} desde el ancla y, si hay rotación, siguiente miembro
     * activo (saltando a quien ya no está; nadie activo → sin asignar). Sin rotación, el asignado se conserva.
     *
     * @param requestOrNull si trae {@code dueDate} y no coincide con la actual → 409 (evita avanzar dos periodos)
     */
    @Transactional
    public TaskResponse complete(UUID householdId, UUID userId, UUID taskId, CompleteTaskRequest requestOrNull) {
        guard.requireMember(householdId, userId);
        Task task = loadForUpdate(householdId, taskId);
        if (requestOrNull != null && requestOrNull.dueDate() != null
                && !requestOrNull.dueDate().equals(task.getDueDate())) {
            throw new ConflictException(TASK_OCCURRENCE_MISMATCH,
                    "La ocurrencia indicada ya no es la actual de la tarea (" + task.getDueDate() + ").");
        }
        if (task.getStatus() == TaskStatus.DONE) {
            throw new ConflictException(TASK_ALREADY_DONE, "La tarea ya está hecha.");
        }
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        LocalDate nextDue = task.isRecurring()
                ? RecurrenceSchedule.nextDueDate(task.getRecurrence(), task.getAnchorDate(), task.getDueDate(), today)
                : null;
        // La API solo admite fechas de los años 1..9999 (D-047): no puede devolver una que ella misma rechaza.
        if (nextDue != null && nextDue.isAfter(Task.MAX_DUE_DATE)) {
            throw new BusinessRuleException(TASK_SCHEDULE_EXHAUSTED,
                    "La siguiente ocurrencia de la tarea cae después de " + Task.MAX_DUE_DATE + ".");
        }
        completions.save(task.recordCompletion(userId, now));
        if (nextDue == null) {
            task.markDone(now);
        } else {
            task.advanceTo(nextDue, nextAssignee(householdId, task), now);
        }
        return TaskMapper.toResponse(task, today);
    }

    /** Solo tareas puntuales hechas vuelven a PENDING; el histórico se conserva. */
    @Transactional
    public TaskResponse reopen(UUID householdId, UUID userId, UUID taskId) {
        guard.requireMember(householdId, userId);
        Task task = loadForUpdate(householdId, taskId);
        if (task.isRecurring() || task.getStatus() != TaskStatus.DONE) {
            throw new ConflictException(TASK_NOT_DONE, "Solo se puede reabrir una tarea puntual hecha.");
        }
        task.reopen(Instant.now(clock));
        return TaskMapper.toResponse(task, today());
    }

    /** Histórico de la tarea, más reciente primero. */
    @Transactional(readOnly = true)
    public PageResponse<TaskCompletionResponse> completions(UUID householdId, UUID userId, UUID taskId, int page,
                                                            int size) {
        guard.requireMember(householdId, userId);
        Task task = load(householdId, taskId);
        return PageResponse.of(completions.findByTaskIdOrderByCompletedAtDescIdDesc(task.getId(),
                PageRequest.of(page, size)), TaskMapper::toResponse);
    }

    private UUID nextAssignee(UUID householdId, Task task) {
        List<UUID> rotation = task.getRotation();
        if (rotation.isEmpty()) {
            return task.getAssigneeId();
        }
        Set<UUID> active = households.getSplitInfo(householdId).activeMembers().stream()
                .map(ActiveMember::userId).collect(Collectors.toSet());
        return TaskRotation.nextAssignee(rotation, task.getAssigneeId(), active).orElse(null);
    }

    /**
     * Asignado y rotación deben ser miembros activos y, con rotación, el asignado uno de ellos. El
     * asignado se comprueba primero: si está en la rotación pero ya no es miembro, el error es suyo.
     */
    private static void requireValidPeople(HouseholdSplitInfo info, TaskRequest request) {
        UUID assignee = request.assigneeId();
        if (assignee != null && !info.isActiveMember(assignee)) {
            throw new BusinessRuleException(ASSIGNEE_NOT_MEMBER, "El asignado debe ser un miembro activo del hogar.");
        }
        List<UUID> rotation = request.rotationOrEmpty();
        if (!rotation.stream().allMatch(info::isActiveMember)) {
            throw new BusinessRuleException(ROTATION_MEMBER_NOT_MEMBER,
                    "Todos los miembros de la rotación deben ser miembros activos del hogar.");
        }
        if (assignee != null && !rotation.isEmpty() && !rotation.contains(assignee)) {
            throw new BusinessRuleException(ASSIGNEE_NOT_IN_ROTATION, "El asignado debe formar parte de la rotación.");
        }
    }

    private static boolean isOwner(HouseholdSplitInfo info, UUID userId) {
        return info.activeMembers().stream()
                .anyMatch(m -> m.userId().equals(userId) && m.role() == HouseholdRole.OWNER);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Bloquea la fila: dos escrituras concurrentes (p. ej. dos {@code complete}) se serializan (D-044). */
    private Task loadForUpdate(UUID householdId, UUID taskId) {
        return tasks.findForUpdate(taskId, householdId)
                .orElseThrow(() -> new NotFoundException(TASK_NOT_FOUND, "La tarea no existe."));
    }

    private Task load(UUID householdId, UUID taskId) {
        return tasks.findByIdAndHouseholdId(taskId, householdId)
                .orElseThrow(() -> new NotFoundException(TASK_NOT_FOUND, "La tarea no existe."));
    }
}
