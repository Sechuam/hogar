package com.hogar.household.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.hogar.shared.validation.HasText;
import com.hogar.household.domain.SplitPolicy;
import jakarta.validation.constraints.Size;

/** PATCH: los campos {@code null} no se modifican. Solo OWNER. La moneda no es editable. */
public record UpdateHouseholdRequest(
        @Schema(description = "Nuevo nombre; ausente o null = sin cambios.", example = "Piso Lavapiés 3ºB")
        @Size(min = 1, max = 100) @HasText String name,
        @Schema(description = "Nueva política de reparto; ausente o null = sin cambios. Solo afecta a gastos futuros. EQUAL = a partes iguales; PROPORTIONAL = proporcional a los ingresos del mes de cada miembro.",
                example = "EQUAL")
        SplitPolicy splitPolicy) {
}
