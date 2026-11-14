package com.hogar.settlement.domain.balance;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Balance calculado de una persona (todos los importes con escala 2).
 * {@code balance = paid − owed + sent − received}: positivo = acreedor, negativo = deudor.
 *
 * <p>Se mapea 1:1 a {@code settlement.dto.MemberBalance} añadiendo si sigue siendo miembro.
 */
public record UserBalance(UUID userId, BigDecimal paid, BigDecimal owed,
                          BigDecimal sent, BigDecimal received, BigDecimal balance) {

    /** {@code true} si tiene algún importe ≠ 0 (para decidir si un ex-miembro aparece en el listado). */
    public boolean hasMovements() {
        return paid.signum() != 0 || owed.signum() != 0 || sent.signum() != 0 || received.signum() != 0;
    }

    /** {@code true} si no debe ni le deben nada. */
    public boolean isSettled() {
        return balance.signum() == 0;
    }
}
