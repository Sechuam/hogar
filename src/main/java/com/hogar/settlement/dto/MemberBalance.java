package com.hogar.settlement.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Balance de una persona en el hogar. {@code balance = paid − owed + settlementsSent − settlementsReceived}.
 * Positivo = el hogar le debe (acreedor); negativo = debe al hogar (deudor). Escala 2.
 *
 * @param active {@code false} = ex-miembro con deudas o créditos pendientes
 */
public record MemberBalance(UUID userId, boolean active, BigDecimal paid, BigDecimal owed,
                            BigDecimal settlementsSent, BigDecimal settlementsReceived, BigDecimal balance) {
}
