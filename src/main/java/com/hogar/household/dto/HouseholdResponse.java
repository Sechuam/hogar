package com.hogar.household.dto;

import com.hogar.household.domain.HouseholdPlan;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import java.time.Instant;
import java.util.UUID;

/** @param myRole rol del usuario que hace la petición en este hogar */
public record HouseholdResponse(
        UUID id,
        String name,
        SplitPolicy splitPolicy,
        HouseholdPlan plan,
        String currency,
        HouseholdRole myRole,
        int memberCount,
        Instant createdAt) {
}
