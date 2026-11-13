package com.hogar.household.service;

import com.hogar.household.config.InvitationProperties;
import com.hogar.household.domain.Household;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.Invitation;
import com.hogar.household.domain.Membership;
import com.hogar.household.dto.CreateInvitationRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.household.repository.InvitationRepository;
import com.hogar.household.repository.MembershipRepository;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.ConflictException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Invitaciones a un hogar: el OWNER las crea, lista y revoca; cualquier usuario autenticado las acepta. */
@Service
public class InvitationService {

    static final String INVITATION_NOT_FOUND = "invitation-not-found";
    static final String ALREADY_MEMBER = "already-member";
    static final String REMOVED_FROM_HOUSEHOLD = "removed-from-household";
    static final String INVITATION_EXPIRED = "invitation-expired";
    static final String INVITATION_EXHAUSTED = "invitation-exhausted";
    /** Intentos de generar un código libre antes de rendirse (con 2^50 códigos, colisionar dos veces es irreal). */
    static final int MAX_CODE_ATTEMPTS = 5;

    private final InvitationRepository invitations;
    private final HouseholdRepository households;
    private final MembershipRepository memberships;
    private final HouseholdMembershipGuard guard;
    private final InvitationCodeGenerator codeGenerator;
    private final InvitationProperties properties;
    private final Clock clock;

    InvitationService(InvitationRepository invitations, HouseholdRepository households,
                      MembershipRepository memberships, HouseholdMembershipGuard guard,
                      InvitationCodeGenerator codeGenerator, InvitationProperties properties, Clock clock) {
        this.invitations = invitations;
        this.households = households;
        this.memberships = memberships;
        this.guard = guard;
        this.codeGenerator = codeGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public InvitationResponse create(UUID householdId, UUID userId, CreateInvitationRequest request) {
        guard.requireOwner(householdId, userId);
        Instant now = Instant.now(clock);
        Invitation invitation = Invitation.create(householdId, uniqueCode(), userId, now,
                now.plus(ttl(request.ttlHours())), request.maxUses());
        return HouseholdMapper.toResponse(invitations.save(invitation));
    }

    /** Todas las invitaciones del hogar (también caducadas y revocadas), las más recientes primero. */
    @Transactional(readOnly = true)
    public List<InvitationResponse> list(UUID householdId, UUID userId) {
        guard.requireOwner(householdId, userId);
        return invitations.findByHouseholdIdOrderByCreatedAtDesc(householdId).stream()
                .map(HouseholdMapper::toResponse)
                .toList();
    }

    /** Revoca la invitación. Idempotente. */
    @Transactional
    public void revoke(UUID householdId, UUID userId, UUID invitationId) {
        guard.requireOwner(householdId, userId);
        Invitation invitation = invitations.findById(invitationId)
                .filter(i -> i.getHouseholdId().equals(householdId))
                .orElseThrow(InvitationService::invitationNotFound);
        invitation.revoke(Instant.now(clock));
    }

    /**
     * Une al usuario al hogar como MEMBER. Orden de comprobación: inexistente/revocada (404),
     * ya miembro (409), caducada (422), agotada (422). Ser ya miembro va antes que caducidad y
     * usos porque es la respuesta más útil para quien reabre un enlace viejo de su propio hogar.
     *
     * <p>Bloqueos, siempre en el mismo orden (invitación → hogar): la fila de la invitación para
     * no perder incrementos de {@code uses}, y la del hogar para serializarse con abandonos y
     * cambios de rol (ver {@link MemberService}).
     */
    @Transactional
    public HouseholdResponse accept(String rawCode, UUID userId) {
        String code = InvitationCodeGenerator.normalize(rawCode);
        if (code.isEmpty() || code.length() > Invitation.MAX_CODE_LENGTH) {
            throw invitationNotFound();
        }
        Invitation invitation = invitations.findByCodeForUpdate(code)
                .filter(i -> !i.isRevoked())
                .orElseThrow(InvitationService::invitationNotFound);
        UUID householdId = invitation.getHouseholdId();
        Household household = households.findByIdForUpdate(householdId)
                .orElseThrow(InvitationService::invitationNotFound);
        long memberCount = memberships.countActive(householdId);
        if (memberCount == 0) {
            // Hogar abandonado por todos: nadie podría administrarlo, la invitación ya no vale.
            throw invitationNotFound();
        }
        if (memberships.existsActive(householdId, userId)) {
            throw new ConflictException(ALREADY_MEMBER, "Ya eres miembro de este hogar.");
        }
        if (memberships.wasRemovedAfter(householdId, userId, invitation.getCreatedAt())) {
            // Expulsado después de crearse esta invitación: no puede usarla para volver (D-028).
            throw new ForbiddenException(REMOVED_FROM_HOUSEHOLD,
                    "Fuiste expulsado de este hogar después de crearse esta invitación. Pide una nueva al OWNER.");
        }
        Instant now = Instant.now(clock);
        if (invitation.isExpired(now)) {
            throw new BusinessRuleException(INVITATION_EXPIRED, "La invitación ha caducado.");
        }
        if (invitation.isExhausted()) {
            throw new BusinessRuleException(INVITATION_EXHAUSTED, "La invitación ya no admite más usos.");
        }
        invitation.registerUse();
        memberships.save(Membership.member(householdId, userId, now));
        return HouseholdMapper.toResponse(household, HouseholdRole.MEMBER, memberCount + 1);
    }

    /** TTL pedido (o el de por defecto), nunca mayor que el máximo configurado. */
    Duration ttl(Integer ttlHours) {
        Duration requested = ttlHours != null ? Duration.ofHours(ttlHours) : properties.defaultTtl();
        return requested.compareTo(properties.maxTtl()) > 0 ? properties.maxTtl() : requested;
    }

    private String uniqueCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            if (invitations.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("No se pudo generar un código de invitación libre");
    }

    private static NotFoundException invitationNotFound() {
        return new NotFoundException(INVITATION_NOT_FOUND, "La invitación no existe o ya no es válida.");
    }
}
