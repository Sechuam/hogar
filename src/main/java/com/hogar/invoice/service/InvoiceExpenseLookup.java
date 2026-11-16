package com.hogar.invoice.service;

import com.hogar.expense.service.ExpenseInvoices;
import com.hogar.invoice.repository.InvoiceRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementa el puerto de expense: qué gastos de una página tienen factura. */
@Service
class InvoiceExpenseLookup implements ExpenseInvoices {

    private final InvoiceRepository invoices;

    InvoiceExpenseLookup(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> withInvoice(Collection<UUID> expenseIds) {
        if (expenseIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(invoices.findExpenseIdsWithInvoice(expenseIds));
    }
}
