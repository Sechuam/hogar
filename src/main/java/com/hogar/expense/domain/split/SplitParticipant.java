package com.hogar.expense.domain.split;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Miembro que participa en el reparto de un gasto.
 *
 * @param joinedAt fecha de alta en el hogar: primer criterio de desempate (luego {@code userId})
 * @param income   ingreso computable del mes del gasto (≥ 0); se ignora en EQUAL
 */
public record SplitParticipant(UUID userId, Instant joinedAt, BigDecimal income) {

    public SplitParticipant {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(income, "income");
        if (income.signum() < 0) {
            throw new IllegalArgumentException("income no puede ser negativo");
        }
    }
}
