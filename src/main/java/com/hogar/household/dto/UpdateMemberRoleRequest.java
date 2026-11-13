package com.hogar.household.dto;

import com.hogar.household.domain.HouseholdRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(@NotNull HouseholdRole role) {
}
