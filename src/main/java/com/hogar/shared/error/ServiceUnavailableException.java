package com.hogar.shared.error;

import org.springframework.http.HttpStatus;

/** Una dependencia externa necesaria (p. ej. las claves públicas de Google) no responde. */
public class ServiceUnavailableException extends DomainException {

    public ServiceUnavailableException(String code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.SERVICE_UNAVAILABLE;
    }
}
