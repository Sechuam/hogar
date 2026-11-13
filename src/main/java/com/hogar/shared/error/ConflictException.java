package com.hogar.shared.error;

import org.springframework.http.HttpStatus;

/** Conflicto con el estado actual (duplicados, versión obsoleta...). */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.CONFLICT;
    }
}
