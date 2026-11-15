package com.hogar.household.dto;

import com.hogar.household.domain.HouseholdPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "Plan del hogar, sus límites y el uso actual.")
public record PlanResponse(
        @Schema(description = "Plan del hogar (FREE o PREMIUM).", example = "FREE")
        HouseholdPlan plan,
        PlanLimits limits,
        PlanUsage usage) {

    @Schema(description = "Uso actual del hogar frente a los límites.")
    public record PlanUsage(
            @Schema(description = "Miembros activos.", example = "3")
            long members,
            @Schema(description = "Bytes ocupados por facturas.", example = "1048576")
            long storageBytes,
            @Schema(description = "Primer día visible en los listados de gastos y liquidaciones; `null` si no hay límite.",
                    example = "2025-12-01", nullable = true)
            LocalDate historyStart) {
    }
}
