package com.hogar.shared.error;

import org.springframework.http.HttpStatus;

/** Recurso no encontrado. Se usa también cuando el recurso existe pero pertenece a otro hogar, para no revelar su existencia. */
public class NotFoundException extends DomainException {

    public NotFoundException(String code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.NOT_FOUND;
    }
}
