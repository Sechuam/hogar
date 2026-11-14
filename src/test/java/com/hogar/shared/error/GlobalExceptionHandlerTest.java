package com.hogar.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.shared.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test unitario (sin contexto de Spring) del formato ProblemDetail de todos los errores. */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FakeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void notFoundExceptionBecomes404ProblemDetail() throws Exception {
        mvc.perform(get("/fake/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://hogar.app/problems/household-not-found"))
                .andExpect(jsonPath("$.code").value("household-not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Hogar no encontrado"));
    }

    @Test
    void businessRuleExceptionBecomes422() throws Exception {
        mvc.perform(get("/fake/business-rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("last-owner-cannot-leave"));
    }

    @Test
    void forbiddenAndConflictMapToTheirStatus() throws Exception {
        mvc.perform(get("/fake/forbidden")).andExpect(status().isForbidden());
        mvc.perform(get("/fake/conflict")).andExpect(status().isConflict());
    }

    @Test
    void tooManyRequestsAddsRetryAfterHeader() throws Exception {
        mvc.perform(get("/fake/too-many"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("rate-limited"));
    }

    @Test
    void optimisticLockBecomes409() throws Exception {
        mvc.perform(get("/fake/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("concurrent-modification"));
    }

    @Test
    void validationErrorsListEveryInvalidField() throws Exception {
        mvc.perform(post("/fake/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-error"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='amount')]").exists());
    }

    @Test
    void malformedJsonIsProblemDetailWithCode() throws Exception {
        mvc.perform(post("/fake/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad-request"));
    }

    @Test
    void wrongJsonTypeIsValidationErrorOfThatField() throws Exception {
        mvc.perform(post("/fake/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok\",\"amount\":\"mucho\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    @Test
    void unanticipatedConstraintViolationIs409WithoutInternals() throws Exception {
        mvc.perform(get("/fake/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("data-conflict"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("uk_"))));
    }

    @Test
    void unexpectedErrorDoesNotLeakInternalMessage() throws Exception {
        mvc.perform(get("/fake/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(jsonPath("$.detail").value("Se ha producido un error inesperado."));
    }

    @Test
    void problemsIncludeRequestIdFromMdc() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        try {
            mvc.perform(get("/fake/not-found"))
                    .andExpect(jsonPath("$.requestId").value("req-42"));
            // Generado por Spring MVC (createResponseEntity): conserva code y añade requestId.
            mvc.perform(post("/fake/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                    .andExpect(jsonPath("$.code").value("bad-request"))
                    .andExpect(jsonPath("$.requestId").value("req-42"));
            mvc.perform(post("/fake/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                    .andExpect(jsonPath("$.code").value("validation-error"))
                    .andExpect(jsonPath("$.requestId").value("req-42"));
            mvc.perform(get("/fake/no-such-route"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("not-found"))
                    .andExpect(jsonPath("$.requestId").value("req-42"));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    @Test
    void withoutRequestInProgressThereIsNoRequestId() throws Exception {
        mvc.perform(get("/fake/not-found"))
                .andExpect(jsonPath("$.requestId").doesNotExist());
    }

    @Test
    void withRequestIdDoesNotOverwriteExistingValue() {
        MDC.put(RequestIdFilter.MDC_KEY, "nuevo");
        try {
            ProblemDetail problem = ProblemDetail.forStatus(400);
            problem.setProperty(Problems.REQUEST_ID, "original");

            assertThat(Problems.withRequestId(problem).getProperties()).containsEntry("requestId", "original");
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    record FakeRequest(@NotBlank String name, @Positive BigDecimal amount) {
    }

    @RestController
    static class FakeController {

        @GetMapping("/fake/not-found")
        void notFound() {
            throw new NotFoundException("household-not-found", "Hogar no encontrado");
        }

        @GetMapping("/fake/business-rule")
        void businessRule() {
            throw new BusinessRuleException("last-owner-cannot-leave", "El último OWNER no puede abandonar el hogar");
        }

        @GetMapping("/fake/forbidden")
        void forbidden() {
            throw new ForbiddenException("owner-required", "Solo el OWNER puede hacer esto");
        }

        @GetMapping("/fake/conflict")
        void conflict() {
            throw new ConflictException("email-already-registered", "El email ya está registrado");
        }

        @GetMapping("/fake/too-many")
        void tooMany() {
            throw new TooManyRequestsException("rate-limited", "Demasiados intentos", 42);
        }

        @GetMapping("/fake/optimistic-lock")
        void optimisticLock() {
            throw new OptimisticLockingFailureException("version mismatch");
        }

        @PostMapping("/fake/validate")
        void validate(@Valid @RequestBody FakeRequest request) {
        }

        @GetMapping("/fake/integrity")
        void integrity() {
            throw new org.springframework.dao.DataIntegrityViolationException("duplicate key uk_users_email");
        }

        @GetMapping("/fake/boom")
        void boom() {
            throw new IllegalStateException("password=secreto en un mensaje interno");
        }
    }
}
