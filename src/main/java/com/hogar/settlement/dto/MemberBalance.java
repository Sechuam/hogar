package com.hogar.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Balance de una persona en el hogar. {@code balance = paid − owed + settlementsSent − settlementsReceived}.
 * Positivo = el hogar le debe (acreedor); negativo = debe al hogar (deudor). Escala 2.
 *
 * @param active {@code false} = ex-miembro con deudas o créditos pendientes
 */
@Schema(description = "Balance de una persona en el hogar.")
public record MemberBalance(
        @Schema(description = "Persona.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID userId,
        @Schema(description = "false = ex-miembro con importes en el hogar.", example = "true")
        boolean active,
        @Schema(description = "Total de gastos que pagó. Decimal con exactamente 2 decimales.",
                example = "320.00")
        BigDecimal paid,
        @Schema(description = "Total de sus partes en los repartos. Decimal con exactamente 2 decimales.",
                example = "277.82")
        BigDecimal owed,
        @Schema(description = "Liquidaciones que pagó. Decimal con exactamente 2 decimales.",
                example = "0.00")
        BigDecimal settlementsSent,
        @Schema(description = "Liquidaciones que recibió. Decimal con exactamente 2 decimales.",
                example = "0.00")
        BigDecimal settlementsReceived,
        @Schema(description = "paid − owed + settlementsSent − settlementsReceived. Positivo = le deben; negativo = debe. Decimal con exactamente 2 decimales.",
                example = "42.18")
        BigDecimal balance) {
}
