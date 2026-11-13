package com.hogar.household.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hogar.household.domain.Household;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.Membership;
import com.hogar.household.dto.MemberResponse;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.household.repository.MembershipRepository;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.DomainException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.user.dto.UserSummary;
import com.hogar.user.service.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    @Mock
    private HouseholdRepository households;
    @Mock
    private MembershipRepository memberships;
    @Mock
    private UserDirectory userDirectory;

    private MemberService service;
    private UUID householdId;
    private final UUID ownerId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new MemberService(households, memberships, new HouseholdMembershipGuard(memberships),
                userDirectory, clock);
        Household household = Household.create("Piso", null, null, NOW.minusSeconds(3600));
        householdId = household.getId();
        lenient().when(households.findByIdForUpdate(householdId)).thenReturn(Optional.of(household));
    }

    private Membership given(UUID userId, HouseholdRole role) {
        Membership m = Membership.join(householdId, userId, role, NOW.minusSeconds(60));
        when(memberships.findActive(householdId, userId)).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void lastOwnerCannotDemoteHimself() {
        Membership owner = given(ownerId, HouseholdRole.OWNER);
        when(memberships.countActiveOwners(householdId)).thenReturn(1L);

        assertThatThrownBy(() -> service.changeRole(householdId, ownerId, ownerId, HouseholdRole.MEMBER))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("last-owner");
        assertThat(owner.getRole()).isEqualTo(HouseholdRole.OWNER);
    }

    @Test
    void ownerCanDemoteHimselfWhenAnotherOwnerExists() {
        Membership owner = given(ownerId, HouseholdRole.OWNER);
        when(memberships.countActiveOwners(householdId)).thenReturn(2L);
        when(userDirectory.findSummaries(anyCollection()))
                .thenReturn(Map.of(ownerId, new UserSummary(ownerId, "Ana", "ana@x.com")));

        MemberResponse response = service.changeRole(householdId, ownerId, ownerId, HouseholdRole.MEMBER);

        assertThat(owner.getRole()).isEqualTo(HouseholdRole.MEMBER);
        assertThat(response.role()).isEqualTo(HouseholdRole.MEMBER);
        assertThat(response.displayName()).isEqualTo("Ana");
    }

    @Test
    void ownerPromotesMember() {
        given(ownerId, HouseholdRole.OWNER);
        Membership other = given(otherId, HouseholdRole.MEMBER);
        when(userDirectory.findSummaries(anyCollection())).thenReturn(Map.of());

        MemberResponse response = service.changeRole(householdId, ownerId, otherId, HouseholdRole.OWNER);

        assertThat(other.getRole()).isEqualTo(HouseholdRole.OWNER);
        assertThat(response.displayName()).isNull();
    }

    @Test
    void memberCannotChangeRoles() {
        given(otherId, HouseholdRole.MEMBER);

        assertThatThrownBy(() -> service.changeRole(householdId, otherId, otherId, HouseholdRole.OWNER))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("owner-role-required");
    }

    @Test
    void changingRoleOfNonMemberIs404() {
        given(ownerId, HouseholdRole.OWNER);
        when(memberships.findActive(householdId, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRole(householdId, ownerId, otherId, HouseholdRole.OWNER))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("member-not-found");
    }

    @Test
    void unknownHouseholdIs404BeforeAnything() {
        UUID unknown = UUID.randomUUID();
        when(households.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leave(unknown, ownerId))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("household-not-found");
    }

    @Test
    void ownerCannotRemoveHimself() {
        given(ownerId, HouseholdRole.OWNER);

        assertThatThrownBy(() -> service.remove(householdId, ownerId, ownerId))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("use-leave-endpoint");
    }

    @Test
    void ownerRemovesMember() {
        given(ownerId, HouseholdRole.OWNER);
        Membership other = given(otherId, HouseholdRole.MEMBER);

        service.remove(householdId, ownerId, otherId);

        assertThat(other.isActive()).isFalse();
        assertThat(other.getLeftAt()).isEqualTo(NOW);
    }

    @Test
    void lastOwnerCannotLeaveWhileOthersRemain() {
        Membership owner = given(ownerId, HouseholdRole.OWNER);
        when(memberships.countActiveOwners(householdId)).thenReturn(1L);
        when(memberships.countActive(householdId)).thenReturn(2L);

        assertThatThrownBy(() -> service.leave(householdId, ownerId))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("last-owner");
        assertThat(owner.isActive()).isTrue();
    }

    @Test
    void soleMemberOwnerCanLeave() {
        Membership owner = given(ownerId, HouseholdRole.OWNER);
        when(memberships.countActiveOwners(householdId)).thenReturn(1L);
        when(memberships.countActive(householdId)).thenReturn(1L);

        service.leave(householdId, ownerId);

        assertThat(owner.isActive()).isFalse();
    }

    @Test
    void ownerCanLeaveWhenAnotherOwnerRemains() {
        Membership owner = given(ownerId, HouseholdRole.OWNER);
        when(memberships.countActiveOwners(householdId)).thenReturn(2L);

        service.leave(householdId, ownerId);

        assertThat(owner.isActive()).isFalse();
    }

    @Test
    void memberCanAlwaysLeave() {
        Membership member = given(otherId, HouseholdRole.MEMBER);

        service.leave(householdId, otherId);

        assertThat(member.isActive()).isFalse();
    }

    @Test
    void listsMembersWithNamesInRepositoryOrder() {
        given(ownerId, HouseholdRole.MEMBER);
        Membership first = Membership.owner(householdId, ownerId, NOW.minusSeconds(100));
        Membership second = Membership.member(householdId, otherId, NOW.minusSeconds(10));
        when(memberships.findActiveByHouseholdIdOrderByJoinedAt(householdId)).thenReturn(List.of(first, second));
        when(userDirectory.findSummaries(List.of(ownerId, otherId))).thenReturn(Map.of(
                ownerId, new UserSummary(ownerId, "Ana", "ana@x.com"),
                otherId, new UserSummary(otherId, "Luis", "luis@x.com")));

        List<MemberResponse> list = service.list(householdId, ownerId);

        assertThat(list).extracting(MemberResponse::displayName).containsExactly("Ana", "Luis");
        assertThat(list).extracting(MemberResponse::role).containsExactly(HouseholdRole.OWNER, HouseholdRole.MEMBER);
    }
}
