package com.hogar.expense.dto;

import java.math.BigDecimal;

/**
 * Totales de un usuario sobre todos los gastos de un hogar (escala 2).
 *
 * @param paid lo que pagó (suma de gastos con {@code paidBy} = el usuario)
 * @param owed lo que le corresponde (suma de sus partes del reparto)
 */
public record ExpenseTotals(BigDecimal paid, BigDecimal owed) {
}
