package com.hogar.income.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Ingresos computables de cada miembro activo en un mes (los que usa el reparto PROPORTIONAL).
 *
 * @param members un elemento por miembro activo, también con 0; {@code percentage} con 2 decimales
 */
@Schema(description = "Ingresos computables de cada miembro activo en un mes (base del reparto PROPORTIONAL).")
public record MonthlyIncomeSummary(
        @Schema(description = "Mes (YYYY-MM).", example = "2026-03", type = "string")
        YearMonth month,
        @Schema(description = "Moneda del hogar.", example = "EUR")
        String currency,
        @Schema(description = "Suma de los ingresos computables del mes. Decimal con exactamente 2 decimales.",
                example = "3000.00")
        BigDecimal total,
        @Schema(description = "Un elemento por miembro activo, también con 0.")
        List<MemberIncome> members) {

    @Schema(description = "Ingresos de un miembro en el mes.")
    public record MemberIncome(
            @Schema(description = "Miembro.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
            UUID userId,
            @Schema(description = "Ingresos computables del mes. Decimal con exactamente 2 decimales.",
                    example = "1800.00")
            BigDecimal amount,
            @Schema(description = "Porcentaje sobre el total, con 2 decimales; los de todos suman 100.00 (o todos 0.00 si el total es 0).",
                    example = "60.00")
            BigDecimal percentage) {
    }
}
