package com.hogar.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Entrada del histórico de una tarea.
 *
 * @param completedBy quien la marcó como hecha (puede no ser el asignado)
 * @param assigneeId  asignado en ese momento (o {@code null})
 * @param dueDate     ocurrencia completada (o {@code null} si la tarea no tenía fecha)
 */
@Schema(description = "Entrada del histórico de una tarea.")
public record TaskCompletionResponse(
        @Schema(description = "ID de la compleción.", example = "3f6c1d2e-8a4b-4c5d-9e7f-0a1b2c3d4e5f")
        UUID id,
        @Schema(description = "Tarea.", example = "c4d5e6f7-1a2b-4c3d-8e9f-5a6b7c8d9e0f")
        UUID taskId,
        @Schema(description = "Quién la marcó como hecha (puede no ser el responsable).",
                example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID completedBy,
        @Schema(description = "Responsable en ese momento, o null.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID assigneeId,
        @Schema(description = "Ocurrencia completada, o null si la tarea no tenía fecha.", example = "2026-03-20")
        LocalDate dueDate,
        @Schema(description = "Cuándo (UTC).", example = "2026-03-15T09:30:00Z")
        Instant completedAt) {
}
