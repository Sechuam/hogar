package com.hogar.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Credenciales o tokens inválidos en los endpoints de autenticación (login, Google, refresh).
 * El mensaje debe ser genérico: nunca indicar si el email existe o qué parte falló.
 */
public class UnauthorizedException extends DomainException {

    public UnauthorizedException(String code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.UNAUTHORIZED;
    }
}
