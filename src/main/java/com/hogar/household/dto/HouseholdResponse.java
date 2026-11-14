package com.hogar.household.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.hogar.household.domain.HouseholdPlan;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import java.time.Instant;
import java.util.UUID;

/** @param myRole rol del usuario que hace la petición en este hogar */
@Schema(description = "Hogar visto por uno de sus miembros.")
public record HouseholdResponse(
        @Schema(description = "ID del hogar.", example = "0b8e6c62-4a5e-4c1f-9d3a-7f2f1e9a4c10")
        UUID id,
        @Schema(description = "Nombre del hogar.", example = "Piso Lavapiés")
        String name,
        @Schema(description = "Política de reparto de los gastos nuevos. EQUAL = a partes iguales; PROPORTIONAL = proporcional a los ingresos del mes de cada miembro.",
                example = "PROPORTIONAL")
        SplitPolicy splitPolicy,
        @Schema(description = "Plan del hogar (FREE o PREMIUM).", example = "FREE")
        HouseholdPlan plan,
        @Schema(description = "Moneda ISO-4217 de todos los importes del hogar.", example = "EUR")
        String currency,
        @Schema(description = "Rol del usuario que hace la petición: OWNER o MEMBER.", example = "OWNER")
        HouseholdRole myRole,
        @Schema(description = "Número de miembros activos.", example = "3")
        int memberCount,
        @Schema(description = "Fecha de creación (UTC).", example = "2026-03-15T09:30:00Z")
        Instant createdAt) {
}
