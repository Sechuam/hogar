package com.hogar.household.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.household.domain.Household;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.Membership;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.user.domain.User;
import com.hogar.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class MembershipRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private HouseholdRepository households;

    @Autowired
    private UserRepository users;

    @Autowired
    private EntityManager em;

    private UUID householdId;
    private UUID ana;
    private UUID bob;
    private UUID carla;

    @BeforeEach
    void setUp() {
        householdId = households.saveAndFlush(Household.create("Piso", null, null, NOW)).getId();
        ana = newUser("ana");
        bob = newUser("bob");
        carla = newUser("carla");
    }

    @Test
    void householdGetsDefaultsAndVersion() {
        em.clear();
        Household household = households.findById(householdId).orElseThrow();

        assertThat(household.getSplitPolicy()).isEqualTo(Household.DEFAULT_SPLIT_POLICY);
        assertThat(household.getCurrency()).isEqualTo("EUR");
        assertThat(household.getVersion()).isZero();
    }

    @Test
    void twoActiveMembershipsOfSameUserInSameHouseholdAreRejected() {
        memberships.saveAndFlush(Membership.owner(householdId, ana, NOW));

        assertThatThrownBy(() -> memberships.saveAndFlush(Membership.member(householdId, ana, NOW.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userCanRejoinAfterLeaving() {
        Membership first = memberships.saveAndFlush(Membership.member(householdId, bob, NOW));
        first.leave(NOW.plusSeconds(60));
        memberships.saveAndFlush(first);

        Membership rejoined = memberships.saveAndFlush(Membership.member(householdId, bob, NOW.plusSeconds(120)));

        assertThat(memberships.findActive(householdId, bob)).contains(rejoined);
        assertThat(memberships.findAll()).filteredOn(m -> m.getUserId().equals(bob)).hasSize(2);
    }

    @Test
    void activeMembershipQueries() {
        Household other = households.saveAndFlush(Household.create("Casa playa", null, null, NOW));
        memberships.saveAndFlush(Membership.member(householdId, carla, NOW.plusSeconds(20)));
        memberships.saveAndFlush(Membership.owner(householdId, ana, NOW));
        Membership leaving = Membership.owner(householdId, bob, NOW.plusSeconds(10));
        leaving.leave(NOW.plusSeconds(30));
        memberships.saveAndFlush(leaving);
        memberships.saveAndFlush(Membership.owner(other.getId(), ana, NOW));
        em.clear();

        assertThat(memberships.findActive(householdId, ana)).get()
                .extracting(Membership::getRole).isEqualTo(HouseholdRole.OWNER);
        assertThat(memberships.findActive(householdId, bob)).isEmpty();
        assertThat(memberships.existsActive(householdId, carla)).isTrue();
        assertThat(memberships.existsActive(householdId, bob)).isFalse();

        assertThat(memberships.findActiveByHouseholdIdOrderByJoinedAt(householdId))
                .extracting(Membership::getUserId).containsExactly(ana, carla);
        assertThat(memberships.findActiveByUserId(ana))
                .extracting(Membership::getHouseholdId).containsExactlyInAnyOrder(householdId, other.getId());
        assertThat(memberships.findActiveByUserId(bob)).isEmpty();

        assertThat(memberships.countActive(householdId)).isEqualTo(2);
        assertThat(memberships.countActiveOwners(householdId)).isEqualTo(1);
    }

    @Test
    void roleChangesArePersisted() {
        Membership membership = memberships.saveAndFlush(Membership.member(householdId, carla, NOW));

        membership.changeRole(HouseholdRole.OWNER);
        memberships.saveAndFlush(membership);
        em.clear();

        assertThat(memberships.countActiveOwners(householdId)).isEqualTo(1);
    }

    private UUID newUser(String name) {
        return users.saveAndFlush(User.registerWithPassword(
                name + "-" + UUID.randomUUID() + "@example.com", name, "hash", NOW)).getId();
    }
}
