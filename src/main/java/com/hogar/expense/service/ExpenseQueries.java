package com.hogar.expense.service;

import com.hogar.expense.dto.ExpenseRef;
import com.hogar.expense.dto.ExpenseTotals;
import java.util.Map;
import java.util.UUID;

/**
 * Contrato público de la feature expense para otras features. No comprueba membresía: quien
 * llama ya ha pasado por {@link com.hogar.shared.security.MembershipGuard}.
 */
public interface ExpenseQueries {

    /** @throws com.hogar.shared.error.NotFoundException {@code expense-not-found} (también si es de otro hogar) */
    ExpenseRef require(UUID householdId, UUID expenseId);

    /**
     * Bloquea la fila del gasto ({@code SELECT … FOR UPDATE}) hasta el fin de la transacción del
     * llamante y comprueba que {@code userId} puede modificarlo (pagador, creador u OWNER). Serializa
     * cualquier cambio de algo que cuelga del gasto con su edición o borrado.
     *
     * @throws com.hogar.shared.error.NotFoundException  {@code expense-not-found}
     * @throws com.hogar.shared.error.ForbiddenException {@code expense-edit-forbidden}
     */
    ExpenseRef lockForModification(UUID householdId, UUID expenseId, UUID userId);

    /**
     * Pagado y debido por usuario sobre <b>todos</b> los gastos del hogar (shares congelados). Solo
     * aparecen usuarios con algún importe; incluye ex-miembros. Σ paid == Σ owed por construcción.
     */
    Map<UUID, ExpenseTotals> totalsByUser(UUID householdId);
}
