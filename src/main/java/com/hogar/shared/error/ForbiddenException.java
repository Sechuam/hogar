package com.hogar.shared.error;

import org.springframework.http.HttpStatus;

/** El usuario es miembro pero no tiene el rol necesario (p. ej. acción reservada al OWNER). */
public class ForbiddenException extends DomainException {

    public ForbiddenException(String code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.FORBIDDEN;
    }
}
