package com.hogar.invoice.service;

import com.hogar.invoice.domain.Invoice;
import com.hogar.invoice.dto.InvoiceResponse;

/** Entidad → DTO. La clave de almacenamiento nunca sale de la capa de servicio. */
final class InvoiceMapper {

    private InvoiceMapper() {
    }

    static InvoiceResponse toResponse(Invoice invoice) {
        return new InvoiceResponse(invoice.getId(), invoice.getExpenseId(), invoice.getContentType(),
                invoice.getSizeBytes(), invoice.getFilename(), invoice.getSha256(), invoice.getUploadedBy(),
                invoice.getCreatedAt());
    }
}
