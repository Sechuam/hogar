package com.hogar.shared.error;

import org.springframework.http.HttpStatus;

/** Límite de peticiones superado (rate limiting). El handler añade la cabecera {@code Retry-After}. */
public class TooManyRequestsException extends DomainException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String code, String message, long retryAfterSeconds) {
        super(code, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }
}
