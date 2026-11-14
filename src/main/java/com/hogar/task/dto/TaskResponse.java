package com.hogar.task.dto;

import com.hogar.task.domain.TaskRecurrence;
import com.hogar.task.domain.TaskStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @param overdue         PENDING y {@code dueDate} anterior a hoy (UTC)
 * @param rotation        orden de rotación (vacío = sin rotación)
 * @param lastCompletedAt última compleción registrada, o {@code null}
 */
public record TaskResponse(
        UUID id,
        String title,
        String description,
        UUID assigneeId,
        TaskRecurrence recurrence,
        LocalDate dueDate,
        TaskStatus status,
        boolean overdue,
        List<UUID> rotation,
        UUID createdBy,
        Instant lastCompletedAt,
        Instant createdAt,
        Instant updatedAt) {
}
