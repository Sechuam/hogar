package com.hogar.task.repository;

import com.hogar.task.domain.Task;
import com.hogar.task.domain.TaskStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Tareas de casa. La rotación se carga con {@code @EntityGraph} en el detalle y en bloque
 * ({@code @BatchSize} en la entidad) en los listados, igual que las partes de un gasto (D-040).
 */
public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    /** Tarea con su rotación, solo si pertenece al hogar (si no, 404 sin filtrar existencia). */
    @EntityGraph(attributePaths = "rotation")
    Optional<Task> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * Tarea bloqueada ({@code SELECT … FOR UPDATE}) para editarla, completarla, reabrirla o borrarla.
     * Sin fetch de la rotación: PostgreSQL no permite {@code FOR UPDATE} sobre el lado nullable de un
     * outer join, y bloquear la raíz basta para serializar las escrituras (D-044). La rotación se carga
     * perezosamente al acceder a ella, ya con la fila bloqueada.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id and t.householdId = :householdId")
    Optional<Task> findForUpdate(UUID id, UUID householdId);

    /**
     * Tareas del hogar paginadas en orden fijo {@code due_date ASC NULLS LAST, created_at ASC, id ASC}
     * (el orden del {@code pageable} se ignora: lo fija el contrato; {@code id} lo hace total para que la
     * paginación no duplique ni pierda filas). Filtros opcionales ({@code null} = sin filtro):
     * <ul>
     *   <li>{@code overdue = true}: PENDING con {@code dueDate < today};</li>
     *   <li>{@code overdue = false}: el resto (DONE, sin fecha o con {@code dueDate ≥ today}).</li>
     * </ul>
     * La rotación se carga en bloque al acceder a ella (dentro de la transacción).
     *
     * @param today "hoy" del llamante ({@code LocalDate.now(clock)}); obligatorio si se filtra por {@code overdue}
     */
    default Page<Task> search(UUID householdId, UUID assigneeIdOrNull, TaskStatus statusOrNull, Boolean overdueOrNull,
            LocalDate today, Pageable pageable) {
        Objects.requireNonNull(householdId, "householdId");
        if (overdueOrNull != null) {
            Objects.requireNonNull(today, "today");
        }
        Specification<Task> spec = (root, query, cb) -> {
            // El orden va en la Specification: Spring Data (criteria) no admite NULLS LAST en un Sort.
            // En la consulta de recuento Spring Data descarta este orden.
            query.orderBy(searchOrder(root, cb));
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("householdId"), householdId));
            if (assigneeIdOrNull != null) {
                predicates.add(cb.equal(root.get("assigneeId"), assigneeIdOrNull));
            }
            if (statusOrNull != null) {
                predicates.add(cb.equal(root.get("status"), statusOrNull));
            }
            if (overdueOrNull != null) {
                Path<LocalDate> dueDate = root.get("dueDate");
                Predicate overdue = cb.and(
                        cb.equal(root.get("status"), TaskStatus.PENDING), cb.lessThan(dueDate, today));
                predicates.add(overdueOrNull
                        ? overdue
                        : cb.or(cb.notEqual(root.get("status"), TaskStatus.PENDING), cb.isNull(dueDate),
                                cb.greaterThanOrEqualTo(dueDate, today)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Pageable unsorted = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())
                : Pageable.unpaged();
        return findAll(spec, unsorted);
    }

    private static List<jakarta.persistence.criteria.Order> searchOrder(Root<Task> root, CriteriaBuilder cb) {
        HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
        return List.of(
                hcb.asc(root.get("dueDate"), false),
                cb.asc(root.get("createdAt")),
                cb.asc(root.get("id")));
    }
}
