package com.hogar.task.dto;

import com.hogar.task.domain.TaskRecurrence;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Alta o reemplazo (PUT) de una tarea.
 *
 * @param assigneeId miembro activo asignado; {@code null} = sin asignar, o el primero de
 *                   {@code rotation} si hay rotación
 * @param dueDate    obligatoria si la tarea es recurrente; fija también la fecha ancla de la periodicidad
 * @param rotation   orden de rotación (miembros activos, sin repetidos, 2–20); solo en tareas recurrentes.
 *                   {@code null} o vacía = sin rotación
 */
public record TaskRequest(
        @NotBlank @Size(max = 100) String title,
        @Size(max = 1000) String description,
        UUID assigneeId,
        @NotNull TaskRecurrence recurrence,
        LocalDate dueDate,
        @Size(max = 20) List<@NotNull UUID> rotation) {

    @AssertTrue(message = "las tareas recurrentes necesitan dueDate")
    public boolean isDueDateValid() {
        return recurrence == null || recurrence == TaskRecurrence.NONE || dueDate != null;
    }

    @AssertTrue(message = "la rotación solo se admite en tareas recurrentes, con al menos 2 miembros distintos")
    public boolean isRotationValid() {
        if (rotation == null || rotation.isEmpty()) {
            return true;
        }
        return recurrence != null && recurrence != TaskRecurrence.NONE
                && rotation.size() >= 2 && !rotation.contains(null)
                && new HashSet<>(rotation).size() == rotation.size();
    }

    public List<UUID> rotationOrEmpty() {
        return rotation == null ? List.of() : rotation;
    }
}
