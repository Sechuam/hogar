package com.hogar.household.service;

import com.hogar.household.domain.Household;
import com.hogar.household.dto.ActiveMember;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.dto.PlanLimits;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.household.repository.MembershipRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class HouseholdQueryService implements HouseholdQueries {

    private final HouseholdRepository households;
    private final MembershipRepository memberships;
    private final HouseholdPlans plans;

    HouseholdQueryService(HouseholdRepository households, MembershipRepository memberships, HouseholdPlans plans) {
        this.households = households;
        this.memberships = memberships;
        this.plans = plans;
    }

    @Override
    @Transactional(readOnly = true)
    public HouseholdSplitInfo getSplitInfo(UUID householdId) {
        Household household = households.findById(householdId)
                .orElseThrow(HouseholdMembershipGuard::householdNotFound);
        var members = memberships.findActiveByHouseholdIdOrderByJoinedAt(householdId).stream()
                .map(m -> new ActiveMember(m.getUserId(), m.getRole(), m.getJoinedAt()))
                .toList();
        return new HouseholdSplitInfo(household.getId(), household.getSplitPolicy(), household.getCurrency(), members);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCurrentOrFormerMember(UUID householdId, UUID userId) {
        return memberships.existsByHouseholdIdAndUserId(householdId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public PlanLimits planLimits(UUID householdId) {
        return households.findById(householdId)
                .map(h -> plans.limits(h.getPlan()))
                .orElseThrow(HouseholdMembershipGuard::householdNotFound);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PlanLimits lockPlanUsage(UUID householdId) {
        return households.findByIdForUpdate(householdId)
                .map(h -> plans.limits(h.getPlan()))
                .orElseThrow(HouseholdMembershipGuard::householdNotFound);
    }
}
