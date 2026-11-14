package com.hogar.expense.dto;

import java.util.UUID;

/**
 * Se publica de forma <b>síncrona, dentro de la transacción</b> que borra el gasto y antes del
 * borrado. Quien escuche puede limpiar lo que cuelga del gasto (p. ej. el fichero de su factura,
 * tras el commit) sin que la feature de gastos dependa de él.
 */
public record ExpenseDeletedEvent(UUID householdId, UUID expenseId) {
}
