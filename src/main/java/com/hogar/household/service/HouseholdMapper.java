package com.hogar.household.service;

import com.hogar.household.domain.Household;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.Invitation;
import com.hogar.household.domain.Membership;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.household.dto.MemberResponse;
import com.hogar.user.dto.UserSummary;

/** Mapeo entidad → DTO. Las entidades no salen de la capa de servicio. */
final class HouseholdMapper {

    private HouseholdMapper() {
    }

    static HouseholdResponse toResponse(Household household, HouseholdRole myRole, long memberCount) {
        return new HouseholdResponse(household.getId(), household.getName(), household.getSplitPolicy(),
                household.getPlan(), household.getCurrency(), myRole, Math.toIntExact(memberCount),
                household.getCreatedAt());
    }

    /** @param user {@code null} si el usuario ya no existe: se devuelve sin nombre ni email */
    static MemberResponse toResponse(Membership membership, UserSummary user) {
        return new MemberResponse(membership.getUserId(),
                user != null ? user.displayName() : null,
                user != null ? user.email() : null,
                membership.getRole(), membership.getJoinedAt());
    }

    static InvitationResponse toResponse(Invitation invitation) {
        return new InvitationResponse(invitation.getId(), invitation.getCode(), invitation.getCreatedAt(),
                invitation.getExpiresAt(), invitation.getMaxUses(), invitation.getUses(), invitation.isRevoked());
    }
}
