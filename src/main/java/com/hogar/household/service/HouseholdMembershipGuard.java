package com.hogar.household.service;

import com.hogar.household.domain.Membership;
import com.hogar.household.repository.MembershipRepository;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Implementación de {@link MembershipGuard}. Basta con buscar la membresía activa: si existe,
 * el hogar existe (FK), y si no existe se responde igual que si el hogar no existiera (D-005).
 */
@Service
class HouseholdMembershipGuard implements MembershipGuard {

    static final String HOUSEHOLD_NOT_FOUND = "household-not-found";
    static final String OWNER_ROLE_REQUIRED = "owner-role-required";

    private final MembershipRepository memberships;

    HouseholdMembershipGuard(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    public void requireMember(UUID householdId, UUID userId) {
        activeMembership(householdId, userId);
    }

    @Override
    public void requireOwner(UUID householdId, UUID userId) {
        ownerMembership(householdId, userId);
    }

    /** Membresía activa del usuario, para quien además necesita el rol. */
    Membership activeMembership(UUID householdId, UUID userId) {
        return memberships.findActive(householdId, userId).orElseThrow(() -> householdNotFound());
    }

    Membership ownerMembership(UUID householdId, UUID userId) {
        Membership membership = activeMembership(householdId, userId);
        if (!membership.isOwner()) {
            throw new ForbiddenException(OWNER_ROLE_REQUIRED, "Esta acción requiere el rol OWNER en el hogar.");
        }
        return membership;
    }

    static NotFoundException householdNotFound() {
        return new NotFoundException(HOUSEHOLD_NOT_FOUND, "El hogar no existe.");
    }
}
