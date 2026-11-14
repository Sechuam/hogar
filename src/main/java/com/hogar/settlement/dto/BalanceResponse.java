package com.hogar.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * @param members miembros activos (aunque estén a 0) y ex-miembros con algún importe; Σ balance == 0
 * @param settled {@code true} si todos los balances son 0
 */
@Schema(description = "Balances calculados del hogar.")
public record BalanceResponse(
        @Schema(description = "Moneda del hogar.", example = "EUR")
        String currency,
        @Schema(description = "Miembros activos (aunque estén a 0) y ex-miembros con algún importe; la suma de balance es 0.00.")
        List<MemberBalance> members,
        @Schema(description = "true si todos los balances son 0.", example = "false")
        boolean settled) {
}
