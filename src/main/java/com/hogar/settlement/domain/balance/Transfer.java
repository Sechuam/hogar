package com.hogar.settlement.domain.balance;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Pago sugerido: {@code from} debería pagar {@code amount} (escala 2, &gt; 0) a {@code to}.
 *
 * @throws IllegalArgumentException si {@code from == to}, el importe no es positivo o no tiene escala 2
 */
public record Transfer(UUID from, UUID to, BigDecimal amount) {

    public Transfer {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        if (from.equals(to)) {
            throw new IllegalArgumentException("una transferencia necesita dos personas distintas: " + from);
        }
        if (amount.signum() <= 0 || amount.scale() != 2) {
            throw new IllegalArgumentException("amount debe ser > 0 con escala 2: " + amount);
        }
    }
}
