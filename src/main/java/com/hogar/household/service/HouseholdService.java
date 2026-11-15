package com.hogar.household.service;

import com.hogar.household.domain.Household;
import com.hogar.household.domain.HouseholdPlan;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.Membership;
import com.hogar.household.dto.CreateHouseholdRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.PlanLimits;
import com.hogar.household.dto.PlanResponse;
import com.hogar.household.dto.UpdateHouseholdRequest;
import com.hogar.household.repository.ActiveMemberCount;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.household.repository.MembershipRepository;
import com.hogar.shared.error.BusinessRuleException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alta, consulta y edición de hogares. El {@code userId} siempre lo pasa el controller. */
@Service
public class HouseholdService {

    private final HouseholdRepository households;
    private final MembershipRepository memberships;
    private final HouseholdMembershipGuard guard;
    private final HouseholdPlans plans;
    private final HouseholdStorageUsage storageUsage;
    private final Clock clock;

    HouseholdService(HouseholdRepository households, MembershipRepository memberships,
                     HouseholdMembershipGuard guard, HouseholdPlans plans, HouseholdStorageUsage storageUsage,
                     Clock clock) {
        this.households = households;
        this.memberships = memberships;
        this.guard = guard;
        this.plans = plans;
        this.storageUsage = storageUsage;
        this.clock = clock;
    }

    /**
     * Crea el hogar (siempre FREE) con su creador como único OWNER.
     *
     * @throws BusinessRuleException {@code plan-household-limit} si ya es OWNER de tantos hogares FREE
     *                               como permite el plan
     */
    @Transactional
    public HouseholdResponse create(UUID userId, CreateHouseholdRequest request) {
        households.lockHouseholdCreation(userId);
        PlanLimits free = plans.limits(HouseholdPlan.FREE);
        if (!free.allowsAnotherOwnedHousehold(households.countFreeOwnedBy(userId))) {
            throw new BusinessRuleException(HouseholdPlans.PLAN_HOUSEHOLD_LIMIT,
                    "El plan FREE permite ser OWNER de " + free.maxOwnedHouseholds()
                            + " hogar(es). Pasa uno a PREMIUM o deja de ser su OWNER para crear otro.");
        }
        Instant now = Instant.now(clock);
        Household household = households.save(
                Household.create(request.name(), request.splitPolicy(), request.currency(), now));
        memberships.save(Membership.owner(household.getId(), userId, now));
        return HouseholdMapper.toResponse(household, HouseholdRole.OWNER, 1);
    }

    /**
     * Hogares donde el usuario tiene membresía activa, en orden de alta. Número de consultas
     * constante (membresías, hogares y recuentos agregados) sea cual sea el número de hogares.
     */
    @Transactional(readOnly = true)
    public List<HouseholdResponse> listMine(UUID userId) {
        List<Membership> mine = memberships.findActiveByUserId(userId);
        if (mine.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = mine.stream().map(Membership::getHouseholdId).toList();
        Map<UUID, Household> byId = households.findAllById(ids).stream()
                .collect(Collectors.toMap(Household::getId, Function.identity()));
        Map<UUID, Long> counts = memberships.countActiveByHouseholdIds(ids).stream()
                .collect(Collectors.toMap(ActiveMemberCount::householdId, ActiveMemberCount::activeMembers));
        return mine.stream()
                .map(m -> HouseholdMapper.toResponse(byId.get(m.getHouseholdId()), m.getRole(),
                        counts.getOrDefault(m.getHouseholdId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public HouseholdResponse get(UUID householdId, UUID userId) {
        Membership me = guard.activeMembership(householdId, userId);
        return toResponse(load(householdId), me);
    }

    /** PATCH: solo cambia los campos no nulos. Solo OWNER. */
    @Transactional
    public HouseholdResponse update(UUID householdId, UUID userId, UpdateHouseholdRequest request) {
        Membership me = guard.ownerMembership(householdId, userId);
        Household household = load(householdId);
        Instant now = Instant.now(clock);
        if (request.name() != null) {
            household.rename(request.name(), now);
        }
        if (request.splitPolicy() != null) {
            household.changeSplitPolicy(request.splitPolicy(), now);
        }
        return toResponse(household, me);
    }

    /** Plan, límites y uso del hogar. Cualquier miembro activo. */
    @Transactional(readOnly = true)
    public PlanResponse plan(UUID householdId, UUID userId) {
        guard.activeMembership(householdId, userId);
        Household household = load(householdId);
        PlanLimits limits = plans.limits(household.getPlan());
        LocalDate historyStart = limits.historyStart(LocalDate.now(clock)).orElse(null);
        return new PlanResponse(household.getPlan(), limits, new PlanResponse.PlanUsage(
                memberships.countActive(householdId), storageUsage.usedBytes(householdId), historyStart));
    }

    private HouseholdResponse toResponse(Household household, Membership me) {
        return HouseholdMapper.toResponse(household, me.getRole(), memberships.countActive(household.getId()));
    }

    private Household load(UUID householdId) {
        return households.findById(householdId).orElseThrow(HouseholdMembershipGuard::householdNotFound);
    }
}
