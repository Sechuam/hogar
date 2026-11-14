package com.hogar.household.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import com.hogar.shared.validation.HasText;
import com.hogar.household.domain.SplitPolicy;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param splitPolicy opcional, por defecto {@code EQUAL}
 * @param currency    opcional, ISO-4217, por defecto {@code EUR}
 */
public record CreateHouseholdRequest(
        @Schema(description = "Nombre del hogar.", example = "Piso Lavapiés")
        @NotNull @HasText @Size(max = 100) String name,
        @Schema(description = "Política de reparto de gastos; opcional, por defecto EQUAL. EQUAL = a partes iguales; PROPORTIONAL = proporcional a los ingresos del mes de cada miembro.",
                example = "PROPORTIONAL")
        SplitPolicy splitPolicy,
        @Schema(description = "Moneda ISO-4217 (3 letras mayúsculas); opcional, por defecto EUR. No se puede cambiar después.",
                example = "EUR")
        @Pattern(regexp = "[A-Z]{3}") String currency) {
}
