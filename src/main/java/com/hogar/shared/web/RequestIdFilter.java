package com.hogar.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Identificador de petición para correlacionar logs y respuestas de error.
 *
 * <p>Si el cliente (o un proxy) envía una cabecera {@value #HEADER} válida
 * ({@code [A-Za-z0-9._-]{1,64}}) se reutiliza; si no, se genera un UUID. Así un valor arbitrario
 * (CRLF, texto enorme) nunca llega a los logs ni a las cabeceras de respuesta.
 *
 * <p>Durante la petición el id está en el MDC ({@value #MDC_KEY}) y se devuelve siempre en la
 * respuesta. Tiene la mayor prioridad, antes de Spring Security, para que también los 401/403
 * lo lleven. El MDC se limpia al terminar aunque la cadena lance una excepción (los hilos de
 * Tomcat se reutilizan).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Id de la petición en curso (del MDC), si la hay. */
    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolve(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
