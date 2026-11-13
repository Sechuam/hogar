package com.hogar.household.repository;

import com.hogar.household.domain.Membership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** "Activa" = {@code leftAt IS NULL}. Las membresías históricas solo se ven con los métodos base. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    @Query("select m from Membership m where m.householdId = :householdId and m.userId = :userId and m.leftAt is null")
    Optional<Membership> findActive(UUID householdId, UUID userId);

    @Query("select count(m) > 0 from Membership m"
            + " where m.householdId = :householdId and m.userId = :userId and m.leftAt is null")
    boolean existsActive(UUID householdId, UUID userId);

    @Query("select m from Membership m where m.userId = :userId and m.leftAt is null order by m.joinedAt")
    List<Membership> findActiveByUserId(UUID userId);

    /** Miembros activos en orden de alta (y por ID para que el orden sea total). */
    @Query("select m from Membership m where m.householdId = :householdId and m.leftAt is null"
            + " order by m.joinedAt, m.id")
    List<Membership> findActiveByHouseholdIdOrderByJoinedAt(UUID householdId);

    @Query("select count(m) from Membership m where m.householdId = :householdId and m.leftAt is null")
    long countActive(UUID householdId);

    @Query("select count(m) from Membership m where m.householdId = :householdId and m.leftAt is null"
            + " and m.role = com.hogar.household.domain.HouseholdRole.OWNER")
    long countActiveOwners(UUID householdId);
}
