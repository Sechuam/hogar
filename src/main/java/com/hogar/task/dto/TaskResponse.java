package com.hogar.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Tarea de casa.")
public record TaskResponse(
        @Schema(description = "ID de la tarea.", example = "3f6c1d2e-8a4b-4c5d-9e7f-0a1b2c3d4e5f")
        UUID id,
        @Schema(description = "Título.", example = "Sacar la basura")
        String title,
        @Schema(description = "Descripción o null.", example = "Orgánico y envases, antes de las 21:00")
        String description,
        @Schema(description = "Responsable actual o null (sin asignar). Puede ser un ex-miembro hasta que la tarea se edite o rote.",
                example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID assigneeId,
        @Schema(description = "Periodicidad: NONE (puntual), DAILY, WEEKLY o MONTHLY.", example = "WEEKLY")
        TaskRecurrence recurrence,
        @Schema(description = "Fecha de la ocurrencia actual o null.", example = "2026-03-20")
        LocalDate dueDate,
        @Schema(description = "Estado: PENDING o DONE (solo las puntuales llegan a DONE; una recurrente sigue PENDING con la siguiente fecha).",
                example = "PENDING")
        TaskStatus status,
        @Schema(description = "PENDING y dueDate anterior a hoy (UTC).", example = "false")
        boolean overdue,
        @Schema(description = "Orden de rotación (vacío = sin rotación).")
        List<UUID> rotation,
        @Schema(description = "Quién la creó.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID createdBy,
        @Schema(description = "Última compleción (UTC) o null.", example = "2026-03-15T09:30:00Z")
        Instant lastCompletedAt,
        @Schema(description = "Alta (UTC).", example = "2026-03-15T09:30:00Z")
        Instant createdAt,
        @Schema(description = "Última modificación (UTC).", example = "2026-03-15T09:30:00Z")
        Instant updatedAt) {
}
