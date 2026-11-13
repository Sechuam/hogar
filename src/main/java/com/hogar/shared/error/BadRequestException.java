package com.hogar.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Parámetros bien formados por separado pero incoherentes entre sí (p. ej. {@code from > to}).
 * Los errores de formato o de Bean Validation no usan esta excepción: los traduce el handler.
 */
public class BadRequestException extends DomainException {

    public BadRequestException(String code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
