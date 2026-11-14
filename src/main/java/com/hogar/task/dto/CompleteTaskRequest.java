package com.hogar.task.dto;

import java.time.LocalDate;

/**
 * Cuerpo opcional de {@code POST /tasks/{id}/complete}.
 *
 * @param dueDate ocurrencia que el cliente cree estar completando. Si se indica y no coincide con la
 *                {@code dueDate} actual → 409 {@code task-occurrence-mismatch}: evita que un doble
 *                toque (o dos miembros a la vez) avance dos periodos.
 */
public record CompleteTaskRequest(LocalDate dueDate) {
}
