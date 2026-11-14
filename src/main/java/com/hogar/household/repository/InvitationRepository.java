package com.hogar.household.repository;

import com.hogar.household.domain.Invitation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByCode(String code);

    /**
     * Como {@link #findByCode} pero con {@code SELECT ... FOR UPDATE}: usar al aceptar, para que
     * dos aceptaciones simultáneas no pierdan un incremento de {@code uses}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.code = :code")
    Optional<Invitation> findByCodeForUpdate(String code);

    /** Vigentes (ni revocadas ni caducadas en {@code now}), las más recientes primero. */
    @Query("select i from Invitation i where i.householdId = :householdId"
            + " and i.revokedAt is null and i.expiresAt > :now order by i.createdAt desc, i.id desc")
    List<Invitation> findPending(UUID householdId, Instant now, Limit limit);

    /** Historial (revocadas o caducadas en {@code now}), las más recientes primero. */
    @Query("select i from Invitation i where i.householdId = :householdId"
            + " and (i.revokedAt is not null or i.expiresAt <= :now) order by i.createdAt desc, i.id desc")
    List<Invitation> findInactive(UUID householdId, Instant now, Limit limit);
}
