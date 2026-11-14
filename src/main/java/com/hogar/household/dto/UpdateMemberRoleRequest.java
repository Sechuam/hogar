package com.hogar.household.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.hogar.household.domain.HouseholdRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(@Schema(description = "Nuevo rol: OWNER (administra el hogar) o MEMBER.", example = "OWNER") @NotNull HouseholdRole role) {
}
