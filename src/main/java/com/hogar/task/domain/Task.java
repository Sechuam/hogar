package com.hogar.task.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;

/**
 * Tarea de casa de un hogar: puntual ({@link TaskRecurrence#NONE}) o recurrente, con asignado y
 * rotación opcionales. Raíz de agregado con bloqueo optimista; la rotación forma parte del agregado
 * y las compleciones no (son {@link TaskCompletion}, sin relación navegable: el histórico crece sin
 * límite y se pagina aparte).
 *
 * <p>Invariantes: una recurrente tiene {@code dueDate} y {@code anchorDate} y siempre está PENDING;
 * {@code anchorDate} es la {@code dueDate} fijada al crear o al cambiar {@code dueDate}/{@code recurrence}
 * y no cambia al completar ({@code anchorDate ≤ dueDate}); la rotación (2–{@value #MAX_ROTATION_SIZE}
 * usuarios distintos) solo existe en recurrentes y, si existe, el asignado es uno de ellos o nadie.
 *
 * <p>Calcular la siguiente fecha o el siguiente asignado no es responsabilidad de esta clase
 * ({@code task.domain.schedule}): {@link #advanceTo} solo aplica el resultado.
 *
 * <p>Hogar, asignado, creador y miembros de la rotación se referencian por ID (D-017).
 */
@Entity
@Table(name = "tasks")
public class Task {

    static final int MAX_TITLE_LENGTH = 100;
    static final int MAX_DESCRIPTION_LENGTH = 1000;
    static final int MIN_ROTATION_SIZE = 2;
    static final int MAX_ROTATION_SIZE = 20;

    /**
     * Rango admitido para la {@code dueDate} que fija quien crea o edita la tarea. Antes del año 1 el
     * driver JDBC lee mal las fechas a.C. que PostgreSQL sí guarda (101 a.C. vuelve como el año 101 y el
     * 29-feb del año 0 no se puede leer: la tarea daba 500 para siempre); por arriba, un año de 5+ cifras
     * acaba fuera del rango de {@code DATE}. Las ocurrencias calculadas al completar pueden pasar de 9999.
     */
    public static final LocalDate MIN_DUE_DATE = LocalDate.of(1, 1, 1);
    public static final LocalDate MAX_DUE_DATE = LocalDate.of(9999, 12, 31);

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskRecurrence recurrence;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "anchor_date")
    private LocalDate anchorDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskStatus status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "last_completed_at")
    private Instant lastCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code null} hasta el primer persist: Spring Data lo usa para distinguir alta de edición (D-015). */
    @Version
    private Long version;

    /**
     * Orden de rotación. {@code @BatchSize}: en un listado, las rotaciones de toda la página se cargan
     * en una sola consulta {@code IN} al acceder a la primera (D-040). Se modifica siempre en sitio
     * (nunca se reasigna) para que Hibernate actualice por posición en vez de borrar y reinsertar.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "task_rotation_members", joinColumns = @JoinColumn(name = "task_id"))
    @OrderColumn(name = "position")
    @Column(name = "user_id", nullable = false)
    @BatchSize(size = 100)
    private List<UUID> rotation = new ArrayList<>();

    protected Task() {
        // JPA
    }

    private Task(UUID householdId, UUID createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.status = TaskStatus.PENDING;
    }

    /**
     * Crea una tarea PENDING con {@code anchorDate = dueDate}.
     *
     * @param assigneeIdOrNull  {@code null} = sin asignar, o el primero de la rotación si la hay
     * @param dueDateOrNull     obligatoria en recurrentes
     * @param rotationOrEmpty   {@code null} o vacía = sin rotación
     * @throws IllegalArgumentException si algún dato no cumple las invariantes de la clase
     */
    public static Task create(UUID householdId, UUID createdBy, String title, String description,
            UUID assigneeIdOrNull, TaskRecurrence recurrence, LocalDate dueDateOrNull, List<UUID> rotationOrEmpty,
            Instant now) {
        Task task = new Task(householdId, createdBy, now);
        task.apply(title, description, assigneeIdOrNull, recurrence, dueDateOrNull, rotationOrEmpty, now);
        task.anchorDate = task.dueDate;
        return task;
    }

    /**
     * Sustituye los datos editables (PUT). Mismas reglas que {@link #create}. Si cambian {@code dueDate}
     * o {@code recurrence}, el ancla pasa a ser la nueva {@code dueDate}; si no, se conserva.
     *
     * @throws IllegalStateException    si la tarea está DONE (hay que reabrirla antes)
     * @throws IllegalArgumentException si algún dato no cumple las invariantes de la clase
     */
    public void update(String title, String description, UUID assigneeIdOrNull, TaskRecurrence recurrence,
            LocalDate dueDateOrNull, List<UUID> rotationOrEmpty, Instant now) {
        if (status == TaskStatus.DONE) {
            throw new IllegalStateException("a done task must be reopened before editing it");
        }
        boolean scheduleChanged = recurrence != this.recurrence || !Objects.equals(dueDateOrNull, this.dueDate);
        apply(title, description, assigneeIdOrNull, recurrence, dueDateOrNull, rotationOrEmpty, now);
        if (scheduleChanged) {
            this.anchorDate = this.dueDate;
        }
    }

    /**
     * Registra que la ocurrencia actual se ha completado: devuelve la entrada del histórico (asignado y
     * {@code dueDate} actuales) y fija {@code lastCompletedAt}. No cambia estado, fecha ni asignado:
     * después hay que llamar a {@link #markDone} (puntual) o {@link #advanceTo} (recurrente).
     *
     * @param completedBy quien la completa (cualquier miembro, no solo el asignado)
     * @throws IllegalStateException si es una tarea puntual ya DONE
     */
    public TaskCompletion recordCompletion(UUID completedBy, Instant now) {
        if (status == TaskStatus.DONE) {
            throw new IllegalStateException("task is already done");
        }
        TaskCompletion completion = TaskCompletion.of(id, completedBy, assigneeId, dueDate, now);
        this.lastCompletedAt = now;
        touch(now);
        return completion;
    }

    /**
     * Marca como hecha una tarea puntual.
     *
     * @throws IllegalStateException si es recurrente o ya está DONE
     */
    public void markDone(Instant now) {
        requireNotRecurring("markDone");
        if (status == TaskStatus.DONE) {
            throw new IllegalStateException("task is already done");
        }
        this.status = TaskStatus.DONE;
        touch(now);
    }

    /**
     * Vuelve a dejar PENDING una tarea puntual hecha. El histórico se conserva.
     *
     * @throws IllegalStateException si es recurrente o no está DONE
     */
    public void reopen(Instant now) {
        requireNotRecurring("reopen");
        if (status != TaskStatus.DONE) {
            throw new IllegalStateException("only a done task can be reopened");
        }
        this.status = TaskStatus.PENDING;
        touch(now);
    }

    /**
     * Pasa una tarea recurrente a su siguiente ocurrencia. El ancla no cambia.
     *
     * @param nextDue            posterior a la {@code dueDate} actual
     * @param nextAssigneeOrNull con rotación: un miembro de ella o {@code null} (ninguno activo); sin
     *                           rotación: debe ser el asignado actual (completar no reasigna)
     * @throws IllegalStateException    si la tarea es puntual
     * @throws IllegalArgumentException si {@code nextDue} no avanza o el asignado no es válido
     */
    public void advanceTo(LocalDate nextDue, UUID nextAssigneeOrNull, Instant now) {
        if (recurrence == TaskRecurrence.NONE) {
            throw new IllegalStateException("only recurring tasks advance to a next occurrence");
        }
        Objects.requireNonNull(nextDue, "nextDue");
        if (!nextDue.isAfter(dueDate)) {
            throw new IllegalArgumentException("next due date " + nextDue + " must be after " + dueDate);
        }
        if (rotation.isEmpty()) {
            if (!Objects.equals(nextAssigneeOrNull, assigneeId)) {
                throw new IllegalArgumentException("without rotation the assignee does not change on completion");
            }
        } else if (nextAssigneeOrNull != null && !rotation.contains(nextAssigneeOrNull)) {
            throw new IllegalArgumentException("next assignee must be part of the rotation");
        }
        this.dueDate = nextDue;
        this.assigneeId = nextAssigneeOrNull;
        touch(now);
    }

    /** PENDING con {@code dueDate} anterior a {@code today}. */
    public boolean isOverdue(LocalDate today) {
        Objects.requireNonNull(today, "today");
        return status == TaskStatus.PENDING && dueDate != null && dueDate.isBefore(today);
    }

    public boolean isRecurring() {
        return recurrence != TaskRecurrence.NONE;
    }

    private void apply(String title, String description, UUID assigneeIdOrNull, TaskRecurrence recurrence,
            LocalDate dueDateOrNull, List<UUID> rotationOrEmpty, Instant now) {
        Objects.requireNonNull(recurrence, "recurrence");
        if (recurrence != TaskRecurrence.NONE && dueDateOrNull == null) {
            throw new IllegalArgumentException("recurring tasks need a due date");
        }
        if (dueDateOrNull != null && (dueDateOrNull.isBefore(MIN_DUE_DATE) || dueDateOrNull.isAfter(MAX_DUE_DATE))) {
            throw new IllegalArgumentException("due date must be between " + MIN_DUE_DATE + " and " + MAX_DUE_DATE);
        }
        List<UUID> newRotation = requireRotation(rotationOrEmpty, recurrence);
        UUID newAssignee = assigneeIdOrNull;
        if (!newRotation.isEmpty()) {
            if (newAssignee == null) {
                newAssignee = newRotation.getFirst();
            } else if (!newRotation.contains(newAssignee)) {
                throw new IllegalArgumentException("assignee must be part of the rotation");
            }
        }
        String newTitle = requireTitle(title);
        String newDescription = normalizeDescription(description);
        Objects.requireNonNull(now, "now");
        // Todo validado: a partir de aquí no se lanza nada y la tarea nunca queda a medio cambiar.
        this.title = newTitle;
        this.description = newDescription;
        this.recurrence = recurrence;
        this.dueDate = dueDateOrNull;
        this.assigneeId = newAssignee;
        replaceRotation(newRotation);
        touch(now);
    }

    /**
     * Sustituye la rotación modificando la colección gestionada en sitio: Hibernate actualiza las
     * posiciones comunes, borra las sobrantes e inserta las nuevas.
     */
    private void replaceRotation(List<UUID> newRotation) {
        if (rotation.equals(newRotation)) {
            return;
        }
        int common = Math.min(rotation.size(), newRotation.size());
        for (int i = 0; i < common; i++) {
            rotation.set(i, newRotation.get(i));
        }
        while (rotation.size() > newRotation.size()) {
            rotation.removeLast();
        }
        rotation.addAll(newRotation.subList(common, newRotation.size()));
    }

    private void requireNotRecurring(String operation) {
        if (recurrence != TaskRecurrence.NONE) {
            throw new IllegalStateException(operation + " only applies to non recurring tasks");
        }
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static List<UUID> requireRotation(List<UUID> rotation, TaskRecurrence recurrence) {
        if (rotation == null || rotation.isEmpty()) {
            return List.of();
        }
        if (recurrence == TaskRecurrence.NONE) {
            throw new IllegalArgumentException("rotation is only allowed in recurring tasks");
        }
        if (rotation.size() < MIN_ROTATION_SIZE || rotation.size() > MAX_ROTATION_SIZE) {
            throw new IllegalArgumentException("rotation must have between " + MIN_ROTATION_SIZE + " and "
                    + MAX_ROTATION_SIZE + " members");
        }
        Set<UUID> distinct = new HashSet<>();
        for (UUID member : rotation) {
            if (!distinct.add(Objects.requireNonNull(member, "rotation member"))) {
                throw new IllegalArgumentException("duplicated rotation member " + member);
            }
        }
        return List.copyOf(rotation);
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        String stripped = title.strip();
        if (stripped.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title must have at most " + MAX_TITLE_LENGTH + " characters");
        }
        return stripped;
    }

    /** Recortada; en blanco equivale a sin descripción. */
    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String stripped = description.strip();
        if (stripped.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "description must have at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return stripped;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public TaskRecurrence getRecurrence() {
        return recurrence;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getAnchorDate() {
        return anchorDate;
    }

    public TaskStatus getStatus() {
        return status;
    }

    /** Orden de rotación (vacío = sin rotación); vista no modificable. */
    public List<UUID> getRotation() {
        return Collections.unmodifiableList(rotation);
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getLastCompletedAt() {
        return lastCompletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Task other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Task[id=" + id + ", householdId=" + householdId + ", status=" + status + "]";
    }
}
