package com.hogar.income.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hogar.shared.validation.HasText;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta o reemplazo (PUT) de un ingreso del usuario autenticado.
 *
 * @param date      fecha del ingreso puntual, o primer mes en que cuenta el recurrente
 * @param recurring {@code true} = se repite cada mes desde {@code date} (hasta {@code endDate} si se indica)
 * @param endDate   solo para recurrentes: último día en que cuenta (incluido su mes)
 */
@Schema(description = "Alta o reemplazo de un ingreso del usuario autenticado.")
public record IncomeRequest(
        @Schema(description = "Importe, de 0.01 a 999999999.99. Decimal con exactamente 2 decimales.",
                example = "1850.00")
        @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @Schema(description = "Concepto (máx. 200).", example = "Nómina")
        @NotNull @HasText @Size(max = 200) String concept,
        @Schema(description = "Fecha del ingreso puntual, o primer mes en que cuenta el recurrente.",
                example = "2026-03-01")
        @NotNull LocalDate date,
        @Schema(description = "true = se repite cada mes desde date (hasta endDate si se indica). Por defecto false.",
                example = "true")
        boolean recurring,
        @Schema(description = "Solo recurrentes: último día en que cuenta (incluido su mes); no anterior a date. Ausente = indefinido.",
                example = "2026-12-31")
        LocalDate endDate) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "endDate solo se admite en ingresos recurrentes y no puede ser anterior a date")
    public boolean isEndDateValid() {
        return endDate == null || (recurring && date != null && !endDate.isBefore(date));
    }
}
