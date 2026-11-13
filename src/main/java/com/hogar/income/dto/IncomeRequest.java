package com.hogar.income.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
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
public record IncomeRequest(
        @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 200) String concept,
        @NotNull LocalDate date,
        boolean recurring,
        LocalDate endDate) {

    @AssertTrue(message = "endDate solo se admite en ingresos recurrentes y no puede ser anterior a date")
    public boolean isEndDateValid() {
        return endDate == null || (recurring && date != null && !endDate.isBefore(date));
    }
}
