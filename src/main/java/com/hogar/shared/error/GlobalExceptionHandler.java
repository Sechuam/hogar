package com.hogar.shared.error;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce cualquier excepción a {@link ProblemDetail}. Extiende
 * {@link ResponseEntityExceptionHandler} para que los errores propios de Spring MVC
 * (JSON mal formado, método no soportado, 404 de ruta...) también salgan como ProblemDetail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        ProblemDetail problem = Problems.of(
                ex.getStatus(), ex.getCode(), ex.getStatus().getReasonPhrase(), ex.getMessage());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex instanceof TooManyRequestsException tooMany) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(tooMany.getRetryAfterSeconds()));
        }
        return response.body(problem);
    }

    /**
     * Versión obsoleta (bloqueo optimista) o bloqueo/deadlock de BD al competir dos escrituras:
     * en ambos casos el cliente puede reintentar sobre el estado actual.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ProblemDetail handleConcurrentModification(ConcurrencyFailureException ex) {
        return Problems.of(HttpStatus.CONFLICT, "concurrent-modification", "Conflict",
                "El recurso ha sido modificado por otra petición. Vuelve a cargarlo e inténtalo de nuevo.");
    }

    /**
     * Restricción de BD violada que el servicio no anticipó (carreras de alta, FK a un usuario
     * inexistente...). Es un conflicto con el estado, no un error del servidor; el detalle
     * interno (nombre de la restricción) no se expone.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        // Solo SQLState y restricción: el mensaje de PostgreSQL ("Failing row contains (...)",
        // "Key (email)=(...)") puede contener datos personales.
        log.warn("Violación de integridad no anticipada: sqlState={}, constraint={}",
                sqlState(ex), constraintName(ex));
        return Problems.of(HttpStatus.CONFLICT, "data-conflict", "Conflict",
                "La operación entra en conflicto con el estado actual de los datos.");
    }

    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return "?";
    }

    private static String constraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return cve.getConstraintName();
            }
        }
        return "?";
    }

    /**
     * Multipart mal formado (cuerpo truncado, sin boundary, cabecera de parte ilegible). Spring lo
     * analiza antes de elegir controller, así que solo se puede capturar aquí. El tamaño excedido
     * (subclase) sigue yendo a {@link #handleMaxUploadSizeExceededException} → 413.
     */
    @ExceptionHandler(MultipartException.class)
    ProblemDetail handleMalformedMultipart(MultipartException ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, "malformed-multipart", "Bad Request",
                "La petición multipart no es válida.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return Problems.of(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "No tienes permisos para realizar esta acción.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Error no controlado", ex);
        // Nunca se filtra el mensaje interno al cliente.
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal Server Error",
                "Se ha producido un error inesperado.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "validation-error", "Bad Request",
                "La petición contiene campos no válidos.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * JSON con un tipo incorrecto en un campo concreto (enum desconocido, texto donde va un número,
     * objeto donde va un texto...) se trata como error de validación de ese campo, con
     * {@code errors[]}. JSON sintácticamente roto o cuerpo ausente sigue siendo {@code bad-request}.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "validation-error", "Bad Request",
                    "La petición contiene campos no válidos.");
            problem.setProperty("errors", List.of(new FieldViolation(
                    fieldPath(mismatch.getPath()), "valor no válido para este campo")));
            return ResponseEntity.badRequest().body(problem);
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    /** Fichero por encima del límite de multipart ({@code spring.servlet.multipart.max-file-size}). */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large", "Payload Too Large",
                "El fichero supera el tamaño máximo permitido.");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    private static String fieldPath(List<JsonMappingException.Reference> path) {
        return path.stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."))
                .replace(".[", "[");
    }

    /** Añade {@code type} y {@code code} a los ProblemDetail que genera Spring MVC. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            String code = HttpStatus.valueOf(statusCode.value()).name().toLowerCase().replace('_', '-');
            problem.setType(URI.create(Problems.TYPE_BASE + code));
            problem.setProperty("code", code);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }
}
