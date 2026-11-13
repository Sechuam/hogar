package com.hogar.income.repository;

import com.hogar.income.domain.Income;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * "Aplica a un mes" ({@link Income#appliesTo}) traducido a SQL sobre {@code [inicio, fin]} del mes:
 * puntual con {@code startDate} dentro del mes, o recurrente con {@code startDate <= fin} y
 * ({@code endDate} nulo o {@code >= inicio}).
 */
public interface IncomeRepository extends JpaRepository<Income, UUID>, JpaSpecificationExecutor<Income> {

    /** Orden del listado: fecha desc; {@code createdAt} e {@code id} hacen el orden total (paginación estable). */
    Sort SEARCH_ORDER = Sort.by(Sort.Order.desc("startDate"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** Ingreso solo si pertenece al hogar (si no, el servicio responde 404 sin filtrar existencia). */
    Optional<Income> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * Ingresos del hogar, paginados y ordenados por {@link #SEARCH_ORDER} (el orden del
     * {@code pageable} se ignora: lo fija el contrato).
     *
     * @param userIdOrNull filtra por miembro
     * @param monthOrNull  solo los que aplican a ese mes
     */
    default Page<Income> search(UUID householdId, UUID userIdOrNull, YearMonth monthOrNull, Pageable pageable) {
        Specification<Income> spec = (root, query, cb) -> {
            Predicate predicate = cb.equal(root.get("householdId"), householdId);
            if (userIdOrNull != null) {
                predicate = cb.and(predicate, cb.equal(root.get("userId"), userIdOrNull));
            }
            if (monthOrNull != null) {
                predicate = cb.and(predicate, appliesTo(root, cb, monthOrNull));
            }
            return predicate;
        };
        Pageable ordered = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), SEARCH_ORDER)
                : Pageable.unpaged(SEARCH_ORDER);
        return findAll(spec, ordered);
    }

    /**
     * Suma por usuario de los ingresos que aplican a {@code month}, en una sola consulta agregada
     * (sin cargar entidades). Solo aparecen usuarios con algún ingreso; importes con escala 2.
     */
    default List<UserIncomeTotal> sumByUserForMonth(UUID householdId, YearMonth month) {
        return sumByUserBetween(householdId, month.atDay(1), month.atEndOfMonth());
    }

    /** Implementación de {@link #sumByUserForMonth}; usar esa. */
    @Query("""
            select i.userId as userId, sum(i.amount) as total
            from Income i
            where i.householdId = :householdId
              and ((i.recurring = false and i.startDate between :monthStart and :monthEnd)
                or (i.recurring = true and i.startDate <= :monthEnd
                    and (i.endDate is null or i.endDate >= :monthStart)))
            group by i.userId
            """)
    List<UserIncomeTotal> sumByUserBetween(UUID householdId, LocalDate monthStart, LocalDate monthEnd);

    /** Total de un usuario en un mes (proyección de {@link #sumByUserForMonth}). */
    interface UserIncomeTotal {

        UUID getUserId();

        BigDecimal getTotal();
    }

    private static Predicate appliesTo(Root<Income> root, CriteriaBuilder cb, YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        Predicate oneOff = cb.and(
                cb.isFalse(root.<Boolean>get("recurring")),
                cb.between(root.<LocalDate>get("startDate"), monthStart, monthEnd));
        Predicate recurring = cb.and(
                cb.isTrue(root.<Boolean>get("recurring")),
                cb.lessThanOrEqualTo(root.<LocalDate>get("startDate"), monthEnd),
                cb.or(cb.isNull(root.get("endDate")),
                        cb.greaterThanOrEqualTo(root.<LocalDate>get("endDate"), monthStart)));
        return cb.or(oneOff, recurring);
    }
}
