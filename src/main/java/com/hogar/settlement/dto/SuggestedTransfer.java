package com.hogar.settlement.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Pago sugerido para saldar cuentas: {@code fromUserId} debería pagar {@code amount} a {@code toUserId}. */
public record SuggestedTransfer(UUID fromUserId, UUID toUserId, BigDecimal amount) {
}
