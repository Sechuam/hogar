package com.hogar.expense.dto;

import java.util.UUID;

/** Referencia mínima a un gasto para otras features (facturas, balances). */
public record ExpenseRef(UUID id, UUID householdId, UUID paidBy, UUID createdBy) {
}
