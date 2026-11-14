package com.hogar.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/** Pago sugerido para saldar cuentas: {@code fromUserId} debería pagar {@code amount} a {@code toUserId}. */
@Schema(description = "Pago sugerido para saldar cuentas.")
public record SuggestedTransfer(
        @Schema(description = "Quién debería pagar.", example = "c4d5e6f7-1a2b-4c3d-8e9f-5a6b7c8d9e0f")
        UUID fromUserId,
        @Schema(description = "Quién debería cobrar.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID toUserId,
        @Schema(description = "Importe. Decimal con exactamente 2 decimales.",
                example = "42.18")
        BigDecimal amount) {
}
