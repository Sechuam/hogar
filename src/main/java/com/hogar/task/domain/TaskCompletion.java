package com.hogar.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Entrada inmutable del histórico de una tarea: quién completó qué ocurrencia y cuándo. Se crea con
 * {@link Task#recordCompletion}; la tarea se referencia por ID (sin relación navegable: el histórico se
 * pagina aparte) y se borra en cascada con ella ({@code ON DELETE CASCADE}).
 *
 * <p>Sin {@code @Version} (nunca se edita): implementa {@link Persistable} para que {@code save()} haga
 * {@code persist} sin SELECT previo (D-015).
 */
@Entity
@Table(name = "task_completions")
public class TaskCompletion implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "completed_by", nullable = false, updatable = false)
    private UUID completedBy;

    @Column(name = "assignee_id", updatable = false)
    private UUID assigneeId;

    @Column(name = "due_date", updatable = false)
    private LocalDate dueDate;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    @Transient
    private boolean isNew = true;

    protected TaskCompletion() {
        // JPA
    }

    private TaskCompletion(UUID taskId, UUID completedBy, UUID assigneeId, LocalDate dueDate, Instant completedAt) {
        this.id = UUID.randomUUID();
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.completedBy = Objects.requireNonNull(completedBy, "completedBy");
        this.assigneeId = assigneeId;
        this.dueDate = dueDate;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    /** Solo desde {@link Task#recordCompletion}, que copia el asignado y la ocurrencia actuales. */
    static TaskCompletion of(UUID taskId, UUID completedBy, UUID assigneeIdOrNull, LocalDate dueDateOrNull,
            Instant completedAt) {
        return new TaskCompletion(taskId, completedBy, assigneeIdOrNull, dueDateOrNull, completedAt);
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getCompletedBy() {
        return completedBy;
    }

    /** Asignado cuando se completó, o {@code null}. */
    public UUID getAssigneeId() {
        return assigneeId;
    }

    /** Ocurrencia completada, o {@code null} si la tarea no tenía fecha. */
    public LocalDate getDueDate() {
        return dueDate;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof TaskCompletion other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "TaskCompletion[id=" + id + ", taskId=" + taskId + ", completedAt=" + completedAt + "]";
    }
}
