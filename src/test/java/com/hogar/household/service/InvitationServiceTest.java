package com.hogar.household.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.hogar.shared.error.DomainException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final String CODE = "ABCDE12345";

    @Mock
    private InvitationRepository invitations;
    @Mock
    private HouseholdRepository households;
    @Mock
    private MembershipRepository memberships;
    @Mock
    private InvitationCodeGenerator codeGenerator;

    private InvitationService service;
    private Household household;
    private UUID householdId;
    private final UUID ownerId = UUID.randomUUID();
    private final UUID joinerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InvitationService(invitations, households, memberships,
                new HouseholdMembershipGuard(memberships), codeGenerator,
                new InvitationProperties(Duration.ofDays(7), Duration.ofDays(30)), Clock.fixed(NOW, ZoneOffset.UTC));
        household = Household.create("Piso", null, null, NOW.minusSeconds(3600));
        householdId = household.getId();
        lenient().when(invitations.save(any(Invitation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void givenOwner() {
        when(memberships.findActive(householdId, ownerId))
                .thenReturn(Optional.of(Membership.owner(householdId, ownerId, NOW.minusSeconds(60))));
    }

    private Invitation givenInvitation(Instant createdAt, Instant expiresAt, Integer maxUses) {
        Invitation invitation = Invitation.create(householdId, CODE, ownerId, createdAt, expiresAt, maxUses);
        when(invitations.findByCodeForUpdate(CODE)).thenReturn(Optional.of(invitation));
        return invitation;
    }

    private void givenHouseholdWithMembers(long count) {
        when(households.findByIdForUpdate(householdId)).thenReturn(Optional.of(household));
        when(memberships.countActive(householdId)).thenReturn(count);
    }

    private static String codeOf(Throwable e) {
        return ((DomainException) e).getCode();
    }

    // --- create / TTL ----------------------------------------------------------------------

    @Test
    void createsInvitationWithDefaultTtlAndUnlimitedUses() {
        givenOwner();
        when(codeGenerator.generate()).thenReturn(CODE);
        when(invitations.findByCode(CODE)).thenReturn(Optional.empty());

        InvitationResponse response = service.create(householdId, ownerId, new CreateInvitationRequest(null, null));

        assertThat(response.code()).isEqualTo(CODE);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(response.maxUses()).isNull();
        assertThat(response.revoked()).isFalse();
    }

    @Test
    void retriesWhenGeneratedCodeCollides() {
        givenOwner();
        when(codeGenerator.generate()).thenReturn("TAKEN00000", CODE);
        when(invitations.findByCode("TAKEN00000")).thenReturn(Optional.of(
                Invitation.create(householdId, "TAKEN00000", ownerId, NOW, NOW.plusSeconds(60), null)));
        when(invitations.findByCode(CODE)).thenReturn(Optional.empty());

        InvitationResponse response = service.create(householdId, ownerId, new CreateInvitationRequest(2, 3));

        assertThat(response.code()).isEqualTo(CODE);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(response.maxUses()).isEqualTo(3);
    }

    @Test
    void givesUpAfterTooManyCollisions() {
        givenOwner();
        when(codeGenerator.generate()).thenReturn(CODE);
        when(invitations.findByCode(CODE)).thenReturn(Optional.of(
                Invitation.create(householdId, CODE, ownerId, NOW, NOW.plusSeconds(60), null)));

        assertThatThrownBy(() -> service.create(householdId, ownerId, new CreateInvitationRequest(null, null)))
                .isInstanceOf(IllegalStateException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void memberCannotCreateInvitations() {
        when(memberships.findActive(householdId, joinerId))
                .thenReturn(Optional.of(Membership.member(householdId, joinerId, NOW)));

        assertThatThrownBy(() -> service.create(householdId, joinerId, new CreateInvitationRequest(null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void ttlIsCappedAtMax() {
        assertThat(service.ttl(24)).isEqualTo(Duration.ofHours(24));
        assertThat(service.ttl(720)).isEqualTo(Duration.ofDays(30));
        assertThat(service.ttl(721)).isEqualTo(Duration.ofDays(30));
        assertThat(service.ttl(null)).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void defaultTtlIsAlsoCappedAtMax() {
        InvitationService capped = new InvitationService(invitations, households, memberships,
                new HouseholdMembershipGuard(memberships), codeGenerator,
                new InvitationProperties(Duration.ofDays(60), Duration.ofDays(30)), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(capped.ttl(null)).isEqualTo(Duration.ofDays(30));
    }

    // --- revoke ----------------------------------------------------------------------------

    @Test
    void revokeIsIdempotentAndKeepsFirstDate() {
        givenOwner();
        Invitation invitation = Invitation.create(householdId, CODE, ownerId, NOW.minusSeconds(600),
                NOW.plusSeconds(600), null);
        invitation.revoke(NOW.minusSeconds(5));
        when(invitations.findById(invitation.getId())).thenReturn(Optional.of(invitation));

        service.revoke(householdId, ownerId, invitation.getId());

        assertThat(invitation.getRevokedAt()).isEqualTo(NOW.minusSeconds(5));
    }

    @Test
    void cannotRevokeInvitationOfAnotherHousehold() {
        givenOwner();
        Invitation foreign = Invitation.create(UUID.randomUUID(), CODE, ownerId, NOW, NOW.plusSeconds(60), null);
        when(invitations.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.revoke(householdId, ownerId, foreign.getId()))
                .isInstanceOf(NotFoundException.class)
                .extracting(InvitationServiceTest::codeOf).isEqualTo("invitation-not-found");
        assertThat(foreign.isRevoked()).isFalse();
    }

    // --- accept ----------------------------------------------------------------------------

    @Test
    void acceptJoinsAsMemberAndCountsUse() {
        Invitation invitation = givenInvitation(NOW.minusSeconds(60), NOW.plusSeconds(60), 2);
        givenHouseholdWithMembers(1);
        when(memberships.existsActive(householdId, joinerId)).thenReturn(false);

        HouseholdResponse response = service.accept("  abcde-12345 ", joinerId);

        assertThat(response.id()).isEqualTo(householdId);
        assertThat(response.myRole()).isEqualTo(HouseholdRole.MEMBER);
        assertThat(response.memberCount()).isEqualTo(2);
        assertThat(invitation.getUses()).isEqualTo(1);
        verify(memberships).save(any(Membership.class));
    }

    @Test
    void acceptNormalizesCrockfordLookalikes() {
        when(invitations.findByCodeForUpdate("0110ABCD12")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept("oiLOabcd12", joinerId))
                .isInstanceOf(NotFoundException.class);
        verify(invitations).findByCodeForUpdate("0110ABCD12");
    }

    @Test
    void unknownCodeIs404() {
        when(invitations.findByCodeForUpdate(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(CODE, joinerId))
                .isInstanceOf(NotFoundException.class)
                .extracting(InvitationServiceTest::codeOf).isEqualTo("invitation-not-found");
    }

    @Test
    void blankOrOverlongCodeIs404WithoutQuerying() {
        assertThatThrownBy(() -> service.accept("   ", joinerId))
                .extracting(InvitationServiceTest::codeOf).isEqualTo("invitation-not-found");
        assertThatThrownBy(() -> service.accept("A".repeat(17), joinerId))
                .extracting(InvitationServiceTest::codeOf).isEqualTo("invitation-not-found");
        verify(invitations, never()).findByCodeForUpdate(any());
    }

    @Test
    void revokedWinsOverEverythingElse() {
        // Revocada, caducada y agotada, y el usuario ya es miembro: lo que se ve es 404.
        Invitation invitation = givenInvitation(NOW.minusSeconds(600), NOW.minusSeconds(60), 1);
        invitation.registerUse();
        invitation.revoke(NOW.minusSeconds(30));

        assertThatThrownBy(() -> service.accept(CODE, ownerId))
                .isInstanceOf(NotFoundException.class)
                .extracting(InvitationServiceTest::codeOf).isEqualTo("invitation-not-found");
    }

    @Test
    void alreadyMemberWinsOverExpiredAndExhausted() {
        Invitation invitation = givenInvitation(NOW.minusSeconds(600), NOW.minusSeconds(60), 1);
        invitation.registerUse();
        givenHouseholdWithMembers(1);
        when(memberships.existsActive(householdId, ownerId)).thenReturn(true);

        assertThatThrownBy(() -> service.accept(CODE, ownerId))
                .extracting(InvitationServiceTest::codeOf).isEqualTo("already-member");
    }

    @Test
    void expiredWinsOverExhausted() {
        Invitation invitation = givenInvitation(NOW.minusSeconds(600), NOW, 1);
        invitation.registerUse();
        givenHouseholdWithMembers(1);

        assertThatThrownBy(() -> service.accept(CODE, joinerId))
                .extracting(InvitationServiceTest::codeOf).isEqualTo("invitation-expired");
    }

    @Test
    void exhaustedInvitationIsRejected() {
        Invitation invitation = givenInvitation(NOW.minusSeconds(600), NOW.plusSeconds(60), 1);
        invitation.registerUse();
        givenHouseholdWithMembers(2);

        assertThatThrownBy(() -> service.accept(CODE, joinerId))
                .extracting(InvitationServiceTest::codeOf).isEqualTo("invitation-exhausted");
        verify(memberships, never()).save(any());
    }

    @Test
    void invitationToAbandonedHouseholdIs404() {
        givenInvitation(NOW.minusSeconds(600), NOW.plusSeconds(60), null);
        givenHouseholdWithMembers(0);

        assertThatThrownBy(() -> service.accept(CODE, joinerId))
                .extracting(InvitationServiceTest::codeOf).isEqualTo("invitation-not-found");
        verify(memberships, never()).save(any());
    }
}
