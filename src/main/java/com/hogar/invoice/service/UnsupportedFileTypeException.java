package com.hogar.invoice.service;

import com.hogar.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** El contenido no es PDF, JPEG, PNG ni WebP (por magic bytes) → 415 {@code unsupported-file-type}. */
public class UnsupportedFileTypeException extends DomainException {

    public static final String CODE = "unsupported-file-type";

    public UnsupportedFileTypeException() {
        super(CODE, "Tipo de fichero no admitido: solo PDF, JPEG, PNG o WebP.");
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
    }
}
