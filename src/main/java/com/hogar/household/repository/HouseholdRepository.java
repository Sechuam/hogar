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
}
