package com.hogar.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.household.domain.SplitPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Gasto con su reparto congelado (snapshot).
 *
 * @param splitPolicyApplied política realmente aplicada (puede ser EQUAL aunque el hogar sea PROPORTIONAL)
 * @param warnings           p. ej. {@code PROPORTIONAL_FALLBACK_NO_INCOME}
 */
@Schema(description = "Gasto con su reparto congelado (no cambia aunque cambien la política o los ingresos).")
public record ExpenseResponse(
        @Schema(description = "ID del gasto.", example = "3f6c1d2e-8a4b-4c5d-9e7f-0a1b2c3d4e5f")
        UUID id,
        @Schema(description = "Importe total. Decimal con exactamente 2 decimales.",
                example = "84.37")
        BigDecimal amount,
        @Schema(description = "Moneda (copiada del hogar al crear).", example = "EUR")
        String currency,
        @Schema(description = "Descripción.", example = "Compra semanal Mercadona")
        String description,
        @Schema(description = "Categoría: RENT (alquiler), GROCERIES (compra), ELECTRICITY, WATER, GAS, INTERNET, CLEANING (limpieza), HOME_SUPPLIES (menaje), OTHER.",
                example = "GROCERIES")
        ExpenseCategory category,
        @Schema(description = "Fecha del gasto.", example = "2026-03-15")
        LocalDate date,
        @Schema(description = "Quién pagó.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID paidBy,
        @Schema(description = "Quién lo registró.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID createdBy,
        @Schema(description = "Política realmente aplicada (EQUAL o PROPORTIONAL); puede ser EQUAL aunque el hogar sea PROPORTIONAL si nadie tenía ingresos ese mes.",
                example = "PROPORTIONAL")
        SplitPolicy splitPolicyApplied,
        @Schema(description = "Reparto entre los miembros activos al crear o recalcular; suma exactamente amount.")
        List<ShareResponse> shares,
        @Schema(description = "Avisos del reparto. PROPORTIONAL_FALLBACK_NO_INCOME = se pidió PROPORTIONAL pero nadie tenía ingresos ese mes y se repartió a partes iguales.",
                allowableValues = {"PROPORTIONAL_FALLBACK_NO_INCOME"})
        List<String> warnings,
        @Schema(description = "Alta (UTC).", example = "2026-03-15T09:30:00Z")
        Instant createdAt,
        @Schema(description = "Última modificación (UTC).", example = "2026-03-15T09:30:00Z")
        Instant updatedAt,
        @Schema(description = "Si tiene factura adjunta (opcional; se gestiona en /expenses/{id}/invoice).",
                example = "true")
        boolean hasInvoice) {

    /**
     * @param weight base del reparto: ingreso del mes en PROPORTIONAL, 1 en EQUAL
     */
    @Schema(description = "Parte de un miembro en el reparto.")
    public record ShareResponse(
            @Schema(description = "Miembro.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
            UUID userId,
            @Schema(description = "Lo que le corresponde pagar. Decimal con exactamente 2 decimales.",
                    example = "50.62")
            BigDecimal amount,
            @Schema(description = "Base del reparto con 2 decimales: ingreso del mes en PROPORTIONAL, 1.00 en EQUAL.",
                    example = "1800.00")
            BigDecimal weight) {
    }
}
