package com.hogar.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Cuerpo opcional de {@code POST /tasks/{id}/complete}.
 *
 * @param dueDate ocurrencia que el cliente cree estar completando. Si se indica y no coincide con la
 *                {@code dueDate} actual → 409 {@code task-occurrence-mismatch}: evita que un doble
 *                toque (o dos miembros a la vez) avance dos periodos.
 */
@Schema(description = "Cuerpo opcional para completar una tarea.")
public record CompleteTaskRequest(
        @Schema(description = "Ocurrencia que crees estar completando; si ya no es la actual → 409 task-occurrence-mismatch. Ausente o null = sin comprobación.",
                example = "2026-03-20")
        LocalDate dueDate) {
}
