package com.hogar.expense.dto;

import com.hogar.expense.domain.ExpenseCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
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
public record ExpenseRequest(
        @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 200) String description,
        @NotNull ExpenseCategory category,
        @NotNull LocalDate date,
        UUID paidBy) {
}
