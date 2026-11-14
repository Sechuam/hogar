package com.hogar.settlement.repository;

import com.hogar.settlement.domain.Settlement;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Liquidaciones (inmutables: se crean y se borran, nunca se editan). */
public interface SettlementRepository
        extends JpaRepository<Settlement, UUID>, JpaSpecificationExecutor<Settlement> {

    /**
     * Orden del listado: fecha desc, alta desc; {@code id} lo hace total (paginación estable). Coincide
     * con el índice {@code ix_settlements_household_date}.
     */
    Sort SEARCH_ORDER = Sort.by(
            Sort.Order.desc("settlementDate"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** Liquidación solo si pertenece al hogar (si no, 404 sin filtrar existencia). */
    Optional<Settlement> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * Como {@link #findByIdAndHouseholdId} pero bloqueando la fila ({@code SELECT … FOR UPDATE}) hasta
     * el fin de la transacción: dos borrados simultáneos se serializan y el segundo ya no la encuentra
     * (404) en vez de fallar al borrar una fila que ya no existe (409).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Settlement s where s.id = :id and s.householdId = :householdId")
    Optional<Settlement> findForUpdate(UUID id, UUID householdId);

    /** Σ importe por pagador ({@code fromUserId}) en el hogar. Usuarios sin liquidaciones no aparecen. */
    @Query("select s.fromUserId as userId, sum(s.amount) as total from Settlement s"
            + " where s.householdId = :householdId group by s.fromUserId")
    List<SettlementUserAmount> sumSentByUser(UUID householdId);

    /** Σ importe por receptor ({@code toUserId}) en el hogar. Usuarios sin liquidaciones no aparecen. */
    @Query("select s.toUserId as userId, sum(s.amount) as total from Settlement s"
            + " where s.householdId = :householdId group by s.toUserId")
    List<SettlementUserAmount> sumReceivedByUser(UUID householdId);

    /**
     * Liquidaciones del hogar paginadas y ordenadas por {@link #SEARCH_ORDER} (el orden del
     * {@code pageable} se ignora: lo fija el contrato). Filtros opcionales ({@code null} = sin filtro):
     * {@code from} y {@code to} inclusivos sobre la fecha; {@code userId} = participa como pagador
     * <em>o</em> receptor.
     */
    default Page<Settlement> search(UUID householdId, LocalDate fromOrNull, LocalDate toOrNull, UUID userIdOrNull,
            Pageable pageable) {
        Objects.requireNonNull(householdId, "householdId");
        Specification<Settlement> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("householdId"), householdId));
            if (fromOrNull != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("settlementDate"), fromOrNull));
            }
            if (toOrNull != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("settlementDate"), toOrNull));
            }
            if (userIdOrNull != null) {
                predicates.add(cb.or(
                        cb.equal(root.get("fromUserId"), userIdOrNull),
                        cb.equal(root.get("toUserId"), userIdOrNull)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Pageable ordered = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), SEARCH_ORDER)
                : Pageable.unpaged(SEARCH_ORDER);
        return findAll(spec, ordered);
    }
}
