package com.hogar.household.service;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.Membership;
import com.hogar.household.dto.MemberResponse;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.household.repository.MembershipRepository;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.user.dto.UserSummary;
import com.hogar.user.service.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Miembros de un hogar: listado, cambio de rol, expulsión y abandono.
 *
 * <p>Invariante: un hogar con miembros activos tiene al menos un OWNER activo. Las operaciones
 * que pueden romperla bloquean antes la fila del hogar ({@link HouseholdRepository#findByIdForUpdate})
 * para que dos peticiones concurrentes no comprueben la regla sobre el mismo estado.
 */
@Service
public class MemberService {

    static final String MEMBER_NOT_FOUND = "member-not-found";
    static final String LAST_OWNER = "last-owner";
    static final String USE_LEAVE_ENDPOINT = "use-leave-endpoint";

    private final HouseholdRepository households;
    private final MembershipRepository memberships;
    private final HouseholdMembershipGuard guard;
    private final UserDirectory userDirectory;
    private final Clock clock;

    MemberService(HouseholdRepository households, MembershipRepository memberships, HouseholdMembershipGuard guard,
                  UserDirectory userDirectory, Clock clock) {
        this.households = households;
        this.memberships = memberships;
        this.guard = guard;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    /** Miembros activos ordenados por fecha de alta. */
    @Transactional(readOnly = true)
    public List<MemberResponse> list(UUID householdId, UUID userId) {
        guard.requireMember(householdId, userId);
        List<Membership> active = memberships.findActiveByHouseholdIdOrderByJoinedAt(householdId);
        Map<UUID, UserSummary> users = userDirectory.findSummaries(active.stream().map(Membership::getUserId).toList());
        return active.stream()
                .map(m -> HouseholdMapper.toResponse(m, users.get(m.getUserId())))
                .toList();
    }

    /** Cambia el rol de un miembro (también el propio). Solo OWNER. */
    @Transactional
    public MemberResponse changeRole(UUID householdId, UUID actorId, UUID targetUserId, HouseholdRole newRole) {
        guard.requireOwner(householdId, actorId);
        lockHousehold(householdId);
        guard.requireOwner(householdId, actorId);
        Membership target = activeMember(householdId, targetUserId);
        if (target.isOwner() && newRole != HouseholdRole.OWNER && memberships.countActiveOwners(householdId) <= 1) {
            throw lastOwner("No puedes dejar el hogar sin OWNER: promociona antes a otro miembro.");
        }
        target.changeRole(newRole);
        UserSummary user = userDirectory.findSummaries(List.of(targetUserId)).get(targetUserId);
        return HouseholdMapper.toResponse(target, user);
    }

    /** Expulsa a otro miembro. Solo OWNER; para salir uno mismo está {@link #leave}. */
    @Transactional
    public void remove(UUID householdId, UUID actorId, UUID targetUserId) {
        guard.requireOwner(householdId, actorId);
        lockHousehold(householdId);
        guard.requireOwner(householdId, actorId);
        if (actorId.equals(targetUserId)) {
            throw new BusinessRuleException(USE_LEAVE_ENDPOINT,
                    "Para salir del hogar usa POST /api/households/{id}/leave.");
        }
        // El que expulsa es OWNER y sigue en el hogar: la expulsión nunca deja el hogar sin OWNER.
        activeMember(householdId, targetUserId).removeBy(actorId, Instant.now(clock));
    }

    /**
     * Abandona el hogar. El último OWNER no puede irse mientras queden otros miembros (debe
     * ceder el rol antes); si es el único miembro sí puede, y el hogar queda sin miembros activos.
     */
    @Transactional
    public void leave(UUID householdId, UUID userId) {
        guard.requireMember(householdId, userId);
        lockHousehold(householdId);
        Membership me = guard.activeMembership(householdId, userId);
        if (me.isOwner()
                && memberships.countActiveOwners(householdId) <= 1
                && memberships.countActive(householdId) > 1) {
            throw lastOwner("Eres el último OWNER: cede el rol a otro miembro antes de salir.");
        }
        me.leave(Instant.now(clock));
    }

    /**
     * Bloquea la fila del hogar. Se llama siempre <b>después</b> de comprobar la membresía sin
     * bloqueo (para que un extraño no pueda bloquear hogares ajenos) y la comprobación se repite
     * ya con el bloqueo, porque el estado pudo cambiar entre medias.
     */
    private void lockHousehold(UUID householdId) {
        households.findByIdForUpdate(householdId).orElseThrow(HouseholdMembershipGuard::householdNotFound);
    }

    private Membership activeMember(UUID householdId, UUID userId) {
        return memberships.findActive(householdId, userId)
                .orElseThrow(() -> new NotFoundException(MEMBER_NOT_FOUND, "El usuario no es miembro del hogar."));
    }

    private static BusinessRuleException lastOwner(String message) {
        return new BusinessRuleException(LAST_OWNER, message);
    }
}
