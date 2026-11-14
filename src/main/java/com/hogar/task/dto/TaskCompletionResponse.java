package com.hogar.task.dto;

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
public record TaskCompletionResponse(UUID id, UUID taskId, UUID completedBy, UUID assigneeId,
                                     LocalDate dueDate, Instant completedAt) {
}
