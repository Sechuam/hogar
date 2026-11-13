package com.hogar.expense.repository;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.domain.SharedExpense;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Gastos comunes. Las partes ({@code shares}) se cargan con un {@code JOIN FETCH} en el detalle y
 * en bloque ({@code @BatchSize} en la entidad) en los listados: un {@code fetch} de colección en
 * una consulta paginada obligaría a Hibernate a paginar en memoria (HHH90003004).
 */
public interface SharedExpenseRepository
        extends JpaRepository<SharedExpense, UUID>, JpaSpecificationExecutor<SharedExpense> {

    /** Orden del listado: fecha desc, alta desc; {@code id} hace el orden total (paginación estable). */
    Sort SEARCH_ORDER = Sort.by(
            Sort.Order.desc("expenseDate"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** Gasto con sus partes, solo si pertenece al hogar (si no, 404 sin filtrar existencia). */
    @EntityGraph(attributePaths = "shares")
    Optional<SharedExpense> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * Gastos del hogar paginados y ordenados por {@link #SEARCH_ORDER} (el orden del {@code pageable}
     * se ignora: lo fija el contrato). Filtros opcionales ({@code null} = sin filtro); {@code from}
     * y {@code to} son inclusivos. Las partes se cargan en bloque al acceder a ellas (dentro de la
     * transacción).
     */
    default Page<SharedExpense> search(UUID householdId, LocalDate fromOrNull, LocalDate toOrNull,
            ExpenseCategory categoryOrNull, UUID paidByOrNull, Pageable pageable) {
        Specification<SharedExpense> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("householdId"), householdId));
            if (fromOrNull != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("expenseDate"), fromOrNull));
            }
            if (toOrNull != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("expenseDate"), toOrNull));
            }
            if (categoryOrNull != null) {
                predicates.add(cb.equal(root.get("category"), categoryOrNull));
            }
            if (paidByOrNull != null) {
                predicates.add(cb.equal(root.get("paidBy"), paidByOrNull));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Pageable ordered = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), SEARCH_ORDER)
                : Pageable.unpaged(SEARCH_ORDER);
        return findAll(spec, ordered);
    }
}
