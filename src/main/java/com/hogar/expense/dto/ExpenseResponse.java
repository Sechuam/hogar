package com.hogar.expense.dto;

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
public record ExpenseResponse(
        UUID id,
        BigDecimal amount,
        String currency,
        String description,
        ExpenseCategory category,
        LocalDate date,
        UUID paidBy,
        UUID createdBy,
        SplitPolicy splitPolicyApplied,
        List<ShareResponse> shares,
        List<String> warnings,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * @param weight base del reparto: ingreso del mes en PROPORTIONAL, 1 en EQUAL
     */
    public record ShareResponse(UUID userId, BigDecimal amount, BigDecimal weight) {
    }
}
