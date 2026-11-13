package com.hogar.household.repository;

import com.hogar.household.domain.Invitation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    List<Invitation> findByHouseholdIdOrderByCreatedAtDesc(UUID householdId);
}
