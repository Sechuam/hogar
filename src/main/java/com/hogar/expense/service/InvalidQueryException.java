package com.hogar.expense.service;

import com.hogar.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** Parámetros de consulta coherentes por separado pero no entre sí (p. ej. {@code from > to}) → 400. */
class InvalidQueryException extends DomainException {

    InvalidQueryException(String code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
