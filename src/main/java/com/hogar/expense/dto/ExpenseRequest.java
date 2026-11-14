package com.hogar.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.hogar.shared.validation.HasText;
import com.hogar.expense.domain.ExpenseCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Alta o reemplazo (PUT) de un gasto común.
 *
 * @param paidBy miembro activo que pagó; {@code null} = el usuario autenticado al crear, o el
 *               pagador actual al editar (PUT)
 */
@Schema(description = "Alta o reemplazo de un gasto común.")
public record ExpenseRequest(
        @Schema(description = "Importe total, de 0.01 a 999999999.99. Decimal con exactamente 2 decimales.",
                example = "84.37")
        @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @Schema(description = "Descripción (máx. 200).", example = "Compra semanal Mercadona")
        @NotNull @HasText @Size(max = 200) String description,
        @Schema(description = "Categoría: RENT (alquiler), GROCERIES (compra), ELECTRICITY, WATER, GAS, INTERNET, CLEANING (limpieza), HOME_SUPPLIES (menaje), OTHER.",
                example = "GROCERIES")
        @NotNull ExpenseCategory category,
        @Schema(description = "Fecha del gasto; en PROPORTIONAL decide qué mes de ingresos se usa.",
                example = "2026-03-15")
        @NotNull LocalDate date,
        @Schema(description = "Miembro activo que pagó. Ausente: al crear, el usuario autenticado; al editar, se conserva el actual.",
                example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID paidBy) {
}
