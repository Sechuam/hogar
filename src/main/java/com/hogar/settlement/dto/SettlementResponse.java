package com.hogar.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Liquidación registrada.")
public record SettlementResponse(
        @Schema(description = "ID de la liquidación.", example = "3f6c1d2e-8a4b-4c5d-9e7f-0a1b2c3d4e5f")
        UUID id,
        @Schema(description = "Quién pagó.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID fromUserId,
        @Schema(description = "Quién recibió.", example = "c4d5e6f7-1a2b-4c3d-8e9f-5a6b7c8d9e0f")
        UUID toUserId,
        @Schema(description = "Importe. Decimal con exactamente 2 decimales.",
                example = "42.18")
        BigDecimal amount,
        @Schema(description = "Moneda (copiada del hogar).", example = "EUR")
        String currency,
        @Schema(description = "Fecha del pago.", example = "2026-03-15")
        LocalDate date,
        @Schema(description = "Nota o null.", example = "Bizum marzo")
        String note,
        @Schema(description = "Quién la registró.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID createdBy,
        @Schema(description = "Registro (UTC).", example = "2026-03-15T09:30:00Z")
        Instant createdAt) {
}
