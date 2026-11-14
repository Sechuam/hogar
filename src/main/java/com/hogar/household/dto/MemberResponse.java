package com.hogar.household.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.hogar.household.domain.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Miembro activo de un hogar.")
public record MemberResponse(
        @Schema(description = "ID del usuario.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID userId,
        @Schema(description = "Nombre visible.", example = "Ana")
        String displayName,
        @Schema(description = "Email.", example = "ana@example.com")
        String email,
        @Schema(description = "Rol en el hogar: OWNER o MEMBER.", example = "MEMBER")
        HouseholdRole role,
        @Schema(description = "Alta en el hogar (UTC).", example = "2026-03-15T09:30:00Z")
        Instant joinedAt) {
}
