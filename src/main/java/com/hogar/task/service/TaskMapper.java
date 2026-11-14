package com.hogar.task.service;

import com.hogar.task.domain.Task;
import com.hogar.task.domain.TaskCompletion;
import com.hogar.task.dto.TaskCompletionResponse;
import com.hogar.task.dto.TaskResponse;
import java.time.LocalDate;
import java.util.List;

/** Mapeo entidad → DTO. Accede a la rotación (LAZY): llamar dentro de la transacción. */
final class TaskMapper {

    private TaskMapper() {
    }

    /** {@code today} decide {@code overdue}: lo pasa el servicio desde su {@code Clock}. */
    static TaskResponse toResponse(Task task, LocalDate today) {
        return new TaskResponse(task.getId(), task.getTitle(), task.getDescription(), task.getAssigneeId(),
                task.getRecurrence(), task.getDueDate(), task.getStatus(), task.isOverdue(today),
                List.copyOf(task.getRotation()), task.getCreatedBy(), task.getLastCompletedAt(),
                task.getCreatedAt(), task.getUpdatedAt());
    }

    static TaskCompletionResponse toResponse(TaskCompletion completion) {
        return new TaskCompletionResponse(completion.getId(), completion.getTaskId(), completion.getCompletedBy(),
                completion.getAssigneeId(), completion.getDueDate(), completion.getCompletedAt());
    }
}
