package com.hogar.shared.error;

import org.springframework.http.HttpStatus;

/** Petición bien formada que viola una regla de negocio. */
public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
