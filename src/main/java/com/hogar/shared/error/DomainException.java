package com.hogar.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Base de las excepciones de negocio. Cada subclase fija su estado HTTP; el {@code code}
 * es un identificador estable que el cliente puede usar (p. ej. para traducir mensajes).
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public abstract HttpStatus getStatus();
}
