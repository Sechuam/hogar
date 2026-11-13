package com.hogar.expense.domain.split;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Parte de un gasto asignada a un participante.
 *
 * @param amount escala 2, ≥ 0
 * @param weight base usada: ingreso del mes en PROPORTIONAL, 1 en EQUAL (escala 2)
 */
public record ShareAllocation(UUID userId, BigDecimal amount, BigDecimal weight) {
}
