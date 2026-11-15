package com.hogar.household.service;

import com.hogar.household.config.PlanProperties;
import com.hogar.household.domain.HouseholdPlan;
import com.hogar.household.dto.PlanLimits;
import org.springframework.stereotype.Component;

/** Traduce un {@link HouseholdPlan} a sus límites (D-068). */
@Component
class HouseholdPlans {

    static final String PLAN_MEMBER_LIMIT = "plan-member-limit";
    static final String PLAN_HOUSEHOLD_LIMIT = "plan-household-limit";

    private final PlanProperties properties;

    HouseholdPlans(PlanProperties properties) {
        this.properties = properties;
    }

    PlanLimits limits(HouseholdPlan plan) {
        return switch (plan) {
            case FREE -> properties.freeLimits();
            case PREMIUM -> PlanLimits.UNLIMITED;
        };
    }
}
