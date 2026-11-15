package com.hogar.invoice.repository;

import com.hogar.invoice.domain.Invoice;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Facturas: como mucho una por gasto ({@code uk_invoices_expense}). */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Factura del gasto, solo si el gasto es del hogar indicado (si no, 404 sin filtrar existencia). */
    Optional<Invoice> findByExpenseIdAndHouseholdId(UUID expenseId, UUID householdId);

    /** Factura del gasto sin filtrar por hogar (p. ej. al reaccionar al borrado del gasto). */
    Optional<Invoice> findByExpenseId(UUID expenseId);

    boolean existsByExpenseId(UUID expenseId);

    /** Bytes ocupados por las facturas del hogar (0 si no tiene). */
    @Query("select coalesce(sum(i.sizeBytes), 0) from Invoice i where i.householdId = :householdId")
    long sumSizeBytes(UUID householdId);
}
