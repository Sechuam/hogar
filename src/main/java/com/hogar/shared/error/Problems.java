package com.hogar.shared.error;

import com.hogar.shared.web.RequestIdFilter;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Construcción uniforme de {@link ProblemDetail} (RFC 7807 / 9457).
 * Todas las respuestas de error llevan un {@code type} estable, la propiedad {@code code} y,
 * dentro de una petición HTTP, {@code requestId} (el mismo que la cabecera {@code X-Request-Id}).
 */
public final class Problems {

    public static final String TYPE_BASE = "https://hogar.app/problems/";
    public static final String REQUEST_ID = RequestIdFilter.MDC_KEY;

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return withRequestId(problem);
    }

    /**
     * Añade el {@code requestId} de la petición en curso (MDC, ver {@link RequestIdFilter}) si
     * no lo tiene ya. Fuera de una petición no hace nada.
     */
    public static ProblemDetail withRequestId(ProblemDetail problem) {
        boolean present = problem.getProperties() != null && problem.getProperties().containsKey(REQUEST_ID);
        if (!present) {
            RequestIdFilter.current().ifPresent(id -> problem.setProperty(REQUEST_ID, id));
        }
        return problem;
    }
}
