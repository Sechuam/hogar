package com.hogar.shared.error;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Construcción uniforme de {@link ProblemDetail} (RFC 7807 / 9457).
 * Todas las respuestas de error llevan un {@code type} estable y la propiedad {@code code}.
 */
public final class Problems {

    public static final String TYPE_BASE = "https://hogar.app/problems/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
