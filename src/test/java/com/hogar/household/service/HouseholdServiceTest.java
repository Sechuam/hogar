package com.hogar.household.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hogar.household.config.PlanProperties;
import com.hogar.household.domain.Household;
import com.hogar.household.domain.HouseholdPlan;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.Membership;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.CreateHouseholdRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.PlanResponse;
import com.hogar.household.dto.UpdateHouseholdRequest;
import com.hogar.household.repository.ActiveMemberCount;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.household.repository.MembershipRepository;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.DomainException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

@ExtendWith(MockitoExtension.class)
class HouseholdServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final PlanProperties FREE_LIMITS = new PlanProperties(4, 1, DataSize.ofMegabytes(50), 12);

    @Mock
    private HouseholdRepository households;
    @Mock
    private MembershipRepository memberships;

    private HouseholdService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HouseholdService(households, memberships, new HouseholdMembershipGuard(memberships),
                new HouseholdPlans(FREE_LIMITS), id -> 2048L, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void creatorBecomesOwner() {
        when(households.save(any(Household.class))).thenAnswer(inv -> inv.getArgument(0));

        HouseholdResponse response = service.create(userId, new CreateHouseholdRequest("  Piso  ", null, null));

        ArgumentCaptor<Membership> membership = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).save(membership.capture());
        assertThat(membership.getValue().getUserId()).isEqualTo(userId);
        assertThat(membership.getValue().isOwner()).isTrue();
        assertThat(membership.getValue().getJoinedAt()).isEqualTo(NOW);
        assertThat(response.name()).isEqualTo("Piso");
        assertThat(response.myRole()).isEqualTo(HouseholdRole.OWNER);
        assertThat(response.memberCount()).isEqualTo(1);
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.splitPolicy()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(response.createdAt()).isEqualTo(NOW);
    }

    @Test
    void listsOnlyActiveMembershipsWithRoleAndCount() {
        Household a = Household.create("A", null, null, NOW);
        Household b = Household.create("B", SplitPolicy.PROPORTIONAL, "USD", NOW);
        when(memberships.findActiveByUserId(userId)).thenReturn(List.of(
                Membership.owner(a.getId(), userId, NOW), Membership.member(b.getId(), userId, NOW)));
        when(households.findAllById(List.of(a.getId(), b.getId()))).thenReturn(List.of(b, a));
        when(memberships.countActiveByHouseholdIds(List.of(a.getId(), b.getId()))).thenReturn(List.of(
                new ActiveMemberCount(b.getId(), 3L), new ActiveMemberCount(a.getId(), 1L)));

        List<HouseholdResponse> list = service.listMine(userId);

        assertThat(list).extracting(HouseholdResponse::name).containsExactly("A", "B");
        assertThat(list).extracting(HouseholdResponse::myRole).containsExactly(HouseholdRole.OWNER, HouseholdRole.MEMBER);
        assertThat(list).extracting(HouseholdResponse::memberCount).containsExactly(1, 3);
        verify(memberships, never()).countActive(any());
    }

    @Test
    void listWithoutMembershipsSkipsHouseholdAndCountQueries() {
        when(memberships.findActiveByUserId(userId)).thenReturn(List.of());

        assertThat(service.listMine(userId)).isEmpty();

        verifyNoInteractions(households);
        verify(memberships, never()).countActiveByHouseholdIds(any());
    }

    @Test
    void nonMemberGets404() {
        UUID householdId = UUID.randomUUID();
        when(memberships.findActive(householdId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(householdId, userId))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("household-not-found");
    }

    @Test
    void ownerUpdatesOnlyGivenFields() {
        Household household = Household.create("Piso", null, null, NOW.minusSeconds(3600));
        UUID id = household.getId();
        when(memberships.findActive(id, userId)).thenReturn(Optional.of(Membership.owner(id, userId, NOW)));
        when(households.findById(id)).thenReturn(Optional.of(household));
        when(memberships.countActive(id)).thenReturn(2L);

        HouseholdResponse response = service.update(id, userId, new UpdateHouseholdRequest(null, SplitPolicy.PROPORTIONAL));

        assertThat(response.name()).isEqualTo("Piso");
        assertThat(response.splitPolicy()).isEqualTo(SplitPolicy.PROPORTIONAL);
        assertThat(response.memberCount()).isEqualTo(2);
        assertThat(household.getUpdatedAt()).isEqualTo(NOW);

        service.update(id, userId, new UpdateHouseholdRequest("Casa", null));
        assertThat(household.getName()).isEqualTo("Casa");
        assertThat(household.getSplitPolicy()).isEqualTo(SplitPolicy.PROPORTIONAL);
    }

    @Test
    void memberCannotUpdate() {
        UUID id = UUID.randomUUID();
        when(memberships.findActive(id, userId)).thenReturn(Optional.of(Membership.member(id, userId, NOW)));

        assertThatThrownBy(() -> service.update(id, userId, new UpdateHouseholdRequest("X", null)))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("owner-role-required");
    }

    @Test
    void ownerOfAFreeHouseholdCannotCreateAnotherOne() {
        when(households.countFreeOwnedBy(userId)).thenReturn(1L);

        assertThatThrownBy(() -> service.create(userId, new CreateHouseholdRequest("Otro", null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("plan-household-limit");
        verify(households).lockHouseholdCreation(userId);
        verify(households, never()).save(any());
    }

    @Test
    void planShowsLimitsAndUsage() {
        Household household = Household.create("Piso", null, null, NOW);
        UUID id = household.getId();
        when(memberships.findActive(id, userId)).thenReturn(Optional.of(Membership.member(id, userId, NOW)));
        when(households.findById(id)).thenReturn(Optional.of(household));
        when(memberships.countActive(id)).thenReturn(3L);

        PlanResponse plan = service.plan(id, userId);

        assertThat(plan.plan()).isEqualTo(HouseholdPlan.FREE);
        assertThat(plan.limits().maxMembers()).isEqualTo(4);
        assertThat(plan.limits().maxStorageBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(plan.usage().members()).isEqualTo(3);
        assertThat(plan.usage().storageBytes()).isEqualTo(2048);
        assertThat(plan.usage().historyStart()).isEqualTo(LocalDate.of(2025, 4, 1));

        household.changePlan(HouseholdPlan.PREMIUM, NOW);
        PlanResponse premium = service.plan(id, userId);
        assertThat(premium.limits().maxMembers()).isNull();
        assertThat(premium.usage().historyStart()).isNull();
    }
}
