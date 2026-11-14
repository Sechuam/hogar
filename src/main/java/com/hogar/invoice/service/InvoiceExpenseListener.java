package com.hogar.invoice.service;

import com.hogar.expense.dto.ExpenseDeletedEvent;
import com.hogar.invoice.repository.InvoiceRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Al borrar un gasto, su factura desaparece de la BD por {@code ON DELETE CASCADE}; aquí se programa
 * el borrado del fichero para después del commit. El evento llega de forma síncrona dentro de la
 * transacción del borrado (que ya tiene bloqueada la fila del gasto), así que una subida concurrente
 * no puede colarse entre la consulta y el borrado.
 */
@Component
class InvoiceExpenseListener {

    private final InvoiceRepository invoices;
    private final InvoiceFileCleanup cleanup;

    InvoiceExpenseListener(InvoiceRepository invoices, InvoiceFileCleanup cleanup) {
        this.invoices = invoices;
        this.cleanup = cleanup;
    }

    @EventListener
    void onExpenseDeleted(ExpenseDeletedEvent event) {
        invoices.findByExpenseIdAndHouseholdId(event.expenseId(), event.householdId())
                .ifPresent(invoice -> cleanup.deleteAfterCommit(invoice.getStorageKey()));
    }
}
