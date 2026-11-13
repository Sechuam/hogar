package com.hogar.income.dto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Ingresos computables de cada miembro activo en un mes (los que usa el reparto PROPORTIONAL).
 *
 * @param members un elemento por miembro activo, también con 0; {@code percentage} con 2 decimales
 */
public record MonthlyIncomeSummary(YearMonth month, String currency, BigDecimal total, List<MemberIncome> members) {

    public record MemberIncome(UUID userId, BigDecimal amount, BigDecimal percentage) {
    }
}
