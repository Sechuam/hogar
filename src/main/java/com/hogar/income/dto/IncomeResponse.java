package com.hogar.income.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Ingreso de un miembro.")
public record IncomeResponse(
        @Schema(description = "ID del ingreso.", example = "3f6c1d2e-8a4b-4c5d-9e7f-0a1b2c3d4e5f")
        UUID id,
        @Schema(description = "Miembro al que pertenece.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID userId,
        @Schema(description = "Importe. Decimal con exactamente 2 decimales.",
                example = "1850.00")
        BigDecimal amount,
        @Schema(description = "Moneda del hogar (ISO-4217).", example = "EUR")
        String currency,
        @Schema(description = "Concepto.", example = "Nómina")
        String concept,
        @Schema(description = "Fecha (o inicio si es recurrente).", example = "2026-03-01")
        LocalDate date,
        @Schema(description = "Si se repite cada mes.", example = "true")
        boolean recurring,
        @Schema(description = "Último día en que cuenta un recurrente; null = indefinido.", example = "2026-12-31")
        LocalDate endDate,
        @Schema(description = "Alta (UTC).", example = "2026-03-15T09:30:00Z")
        Instant createdAt) {
}
