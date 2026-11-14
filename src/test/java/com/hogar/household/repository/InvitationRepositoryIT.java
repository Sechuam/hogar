package com.hogar.household.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.household.domain.Household;
import com.hogar.household.domain.Invitation;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.user.domain.User;
import com.hogar.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class InvitationRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");
    private static final Instant EXPIRES = NOW.plus(Duration.ofDays(7));

    @Autowired
    private InvitationRepository invitations;

    @Autowired
    private HouseholdRepository households;

    @Autowired
    private UserRepository users;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID householdId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        householdId = households.saveAndFlush(Household.create("Piso", null, null, NOW)).getId();
        ownerId = users.saveAndFlush(User.registerWithPassword(
                "owner-" + UUID.randomUUID() + "@example.com", "Owner", "hash", NOW)).getId();
    }

    @Test
    void findsByCodeAndPersistsUsesAndRevocation() {
        Invitation invitation = invitations.saveAndFlush(
                Invitation.create(householdId, "ABCDEFGH12", ownerId, NOW, EXPIRES, 2));
        invitation.registerUse();
        invitation.revoke(NOW.plusSeconds(5));
        invitations.saveAndFlush(invitation);
        em.clear();

        Invitation found = invitations.findByCodeForUpdate("ABCDEFGH12").orElseThrow();

        assertThat(found.getUses()).isEqualTo(1);
        assertThat(found.isRevoked()).isTrue();
        assertThat(invitations.findByCode("ABCDEFGH12")).contains(found);
        assertThat(invitations.findByCode("NOPE")).isEmpty();
    }

    @Test
    void codeIsUnique() {
        invitations.saveAndFlush(Invitation.create(householdId, "DUPLICATE1", ownerId, NOW, EXPIRES, null));

        assertThatThrownBy(() -> invitations.saveAndFlush(
                Invitation.create(householdId, "DUPLICATE1", ownerId, NOW, EXPIRES, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseNeverAllowsMoreUsesThanMaxUses() {
        Invitation invitation = invitations.saveAndFlush(
                Invitation.create(householdId, "LIMITED001", ownerId, NOW, EXPIRES, 1));

        assertThatThrownBy(() -> jdbc.update("update invitations set uses = 2 where id = ?", invitation.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void separatesPendingFromInactiveNewestFirstAndOnlyThisHousehold() {
        Invitation older = invitations.saveAndFlush(
                Invitation.create(householdId, "OLDER00001", ownerId, NOW, EXPIRES, null));
        Invitation newer = invitations.saveAndFlush(
                Invitation.create(householdId, "NEWER00001", ownerId, NOW.plusSeconds(60), EXPIRES, null));
        Invitation revoked = Invitation.create(householdId, "REVOK00001", ownerId, NOW.plusSeconds(30), EXPIRES, null);
        revoked.revoke(NOW.plusSeconds(90));
        invitations.saveAndFlush(revoked);
        Invitation expired = invitations.saveAndFlush(Invitation.create(householdId, "EXPIR00001", ownerId,
                NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(3)), null));
        // Caduca justo en NOW: expiresAt es exclusivo, ya no está vigente.
        Invitation boundary = invitations.saveAndFlush(Invitation.create(householdId, "BOUND00001", ownerId,
                NOW.minus(Duration.ofDays(1)), NOW, null));
        UUID otherHousehold = households.saveAndFlush(Household.create("Otro", null, null, NOW)).getId();
        invitations.saveAndFlush(Invitation.create(otherHousehold, "OTHER00001", ownerId, NOW, EXPIRES, null));
        em.clear();

        assertThat(invitations.findPending(householdId, NOW, Limit.of(10))).containsExactly(newer, older);
        assertThat(invitations.findPending(householdId, NOW, Limit.of(1))).containsExactly(newer);
        assertThat(invitations.findInactive(householdId, NOW, Limit.of(10))).containsExactly(revoked, boundary, expired);
        assertThat(invitations.findInactive(householdId, NOW, Limit.of(2))).containsExactly(revoked, boundary);
    }
}
