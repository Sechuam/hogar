package com.hogar.invoice.service;

import com.hogar.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** La factura supera {@code hogar.invoices.max-size} → 413 {@code file-too-large}. */
public class FileTooLargeException extends DomainException {

    public static final String CODE = "file-too-large";

    private final long maxBytes;

    public FileTooLargeException(long maxBytes) {
        super(CODE, "El fichero supera el tamaño máximo permitido (" + maxBytes + " bytes).");
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.PAYLOAD_TOO_LARGE;
    }
}
