package com.hogar.household.repository;

import com.hogar.household.domain.Household;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface HouseholdRepository extends JpaRepository<Household, UUID> {

    /**
     * {@code SELECT ... FOR UPDATE} del hogar. Serializa las operaciones que cambian el conjunto
     * de miembros u OWNERs (cambio de rol, expulsión, abandono, aceptar invitación): sin él, dos
     * OWNERs degradándose a la vez verían cada uno "queda otro OWNER" y el hogar quedaría sin OWNER.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Household h where h.id = :id")
    Optional<Household> findByIdForUpdate(UUID id);

    /**
     * Bloqueo consultivo de PostgreSQL por usuario hasta el fin de la transacción: serializa las altas
     * de hogar de un mismo usuario para que dos peticiones a la vez no superen el límite del plan FREE.
     * {@code pg_advisory_xact_lock} devuelve {@code void}; el cast a texto da una fila que leer.
     */
    @Query(value = "select cast(pg_advisory_xact_lock(hashtextextended(cast(:userId as text), 0)) as text)",
            nativeQuery = true)
    String lockHouseholdCreation(UUID userId);

    /** Hogares FREE en los que el usuario es OWNER activo. */
    @Query("select count(m) from Membership m join Household h on h.id = m.householdId"
            + " where m.userId = :userId and m.leftAt is null"
            + " and m.role = com.hogar.household.domain.HouseholdRole.OWNER"
            + " and h.plan = com.hogar.household.domain.HouseholdPlan.FREE")
    long countFreeOwnedBy(UUID userId);
}
