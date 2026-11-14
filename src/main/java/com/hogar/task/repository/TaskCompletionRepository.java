package com.hogar.task.repository;

import com.hogar.task.domain.TaskCompletion;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Histórico de compleciones. Se borra en cascada con la tarea ({@code ON DELETE CASCADE} en BD). */
public interface TaskCompletionRepository extends JpaRepository<TaskCompletion, UUID> {

    /**
     * Histórico de una tarea, más reciente primero ({@code id} desempata: orden total para paginar).
     * El {@code Sort} del {@code pageable} se combina con el del nombre del método: el servicio debe
     * pasar un {@code Pageable} sin orden.
     */
    Page<TaskCompletion> findByTaskIdOrderByCompletedAtDescIdDesc(UUID taskId, Pageable pageable);
}
