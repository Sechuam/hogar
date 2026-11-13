package com.hogar.household.dto;

import com.hogar.household.domain.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

public record MemberResponse(UUID userId, String displayName, String email, HouseholdRole role, Instant joinedAt) {
}
