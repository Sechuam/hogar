package com.hogar.expense.service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Puerto que implementa la feature invoice para saber qué gastos tienen factura. Así expense puede
 * indicarlo en sus respuestas sin depender de invoice (la dependencia va al revés, como
 * {@code HouseholdStorageUsage}).
 */
public interface ExpenseInvoices {

    /** De {@code expenseIds}, los que tienen factura adjunta. Una sola consulta para toda la colección. */
    Set<UUID> withInvoice(Collection<UUID> expenseIds);
}
