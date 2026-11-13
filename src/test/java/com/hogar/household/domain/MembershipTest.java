package com.hogar.household.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MembershipTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void factoriesSetRole() {
        assertThat(Membership.owner(HOUSEHOLD, USER, NOW).isOwner()).isTrue();
        Membership member = Membership.member(HOUSEHOLD, USER, NOW);
        assertThat(member.getRole()).isEqualTo(HouseholdRole.MEMBER);
        assertThat(member.isActive()).isTrue();
        assertThat(member.getJoinedAt()).isEqualTo(NOW);
    }

    @Test
    void leaveClosesMembershipOnce() {
        Membership membership = Membership.member(HOUSEHOLD, USER, NOW);

        membership.leave(NOW.plusSeconds(60));

        assertThat(membership.isActive()).isFalse();
        assertThat(membership.getLeftAt()).isEqualTo(NOW.plusSeconds(60));
        assertThatThrownBy(() -> membership.leave(NOW.plusSeconds(120))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> membership.changeRole(HouseholdRole.OWNER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changeRole() {
        Membership membership = Membership.member(HOUSEHOLD, USER, NOW);

        membership.changeRole(HouseholdRole.OWNER);

        assertThat(membership.getRole()).isEqualTo(HouseholdRole.OWNER);
    }
}
