package com.hogar.invoice.service;

import com.hogar.household.service.HouseholdStorageUsage;
import com.hogar.invoice.repository.InvoiceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementa el puerto de household con el espacio que ocupan las facturas de un hogar. */
@Service
class InvoiceStorageUsage implements HouseholdStorageUsage {

    private final InvoiceRepository invoices;

    InvoiceStorageUsage(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Override
    @Transactional(readOnly = true)
    public long usedBytes(UUID householdId) {
        return invoices.sumSizeBytes(householdId);
    }
}
