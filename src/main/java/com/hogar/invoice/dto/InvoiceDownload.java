package com.hogar.invoice.dto;

import java.io.InputStream;
import java.util.Objects;

/**
 * Factura lista para descargar: metadatos para las cabeceras y el contenido ya abierto. Quien la
 * recibe es responsable de cerrar {@code content} (al servirla, lo hace el conversor de Spring).
 */
public record InvoiceDownload(InvoiceResponse metadata, InputStream content) {

    public InvoiceDownload {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(content, "content");
    }
}
