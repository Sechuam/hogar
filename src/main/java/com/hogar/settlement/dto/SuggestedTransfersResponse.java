package com.hogar.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Como mucho n−1 pagos (n = personas con balance distinto de 0); vacío si todo está saldado. */
@Schema(description = "Pagos sugeridos para dejar todos los balances a 0.")
public record SuggestedTransfersResponse(
        @Schema(description = "Moneda del hogar.", example = "EUR")
        String currency,
        @Schema(description = "Como mucho n−1 pagos (n = personas con balance distinto de 0); vacío si todo está saldado.")
        List<SuggestedTransfer> transfers) {
}
