package com.hogar.task.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.task.domain.TaskRecurrence;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static Set<String> invalidFields(TaskRequest request) {
        Set<ConstraintViolation<TaskRequest>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static TaskRequest titled(String title) {
        return new TaskRequest(title, null, null, TaskRecurrence.NONE, null, null);
    }

    /** Todo lo que {@code String.isBlank()} (la entidad) considera blanco debe ser 400, no 500. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "\u2003", "\u3000\u2028", "\u001c", " \u2003 \u205f "})
    void blankTitlesForJavaAreValidationErrors(String title) {
        assertThat(title.isBlank()).isTrue();
        assertThat(invalidFields(titled(title))).containsExactly("title");
    }

    @Test
    void titleWithAnyVisibleCharacterIsValidAndNullIsNot() {
        assertThat(invalidFields(titled(" \u2003a\n"))).isEmpty();
        assertThat(invalidFields(titled("\u00a0"))).as("NBSP no es blanco para Java").isEmpty();
        assertThat(invalidFields(titled(null))).containsExactly("title");
    }

    @Test
    void hugeWhitespaceTitleIsRejectedInLinearTime() {
        String spaces = " ".repeat(2_000_000);
        long start = System.nanoTime();
        assertThat(invalidFields(titled(spaces))).containsExactly("title");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(2));
    }

    private static TaskRequest weekly(List<UUID> rotation) {
        return new TaskRequest("Basura", null, null, TaskRecurrence.WEEKLY, LocalDate.of(2026, 1, 5), rotation);
    }

    @Test
    void immutableRotationListIsValidatedWithoutNullPointerException() {
        assertThat(weekly(List.of(UUID.randomUUID(), UUID.randomUUID())).isRotationValid()).isTrue();
    }

    @Test
    void repeatedMembersOrSingleMemberRotationAreInvalid() {
        UUID a = UUID.randomUUID();
        assertThat(weekly(List.of(a, a)).isRotationValid()).isFalse();
        assertThat(weekly(List.of(a)).isRotationValid()).isFalse();
    }

    @Test
    void rotationRequiresRecurrence() {
        TaskRequest once = new TaskRequest("Basura", null, null, TaskRecurrence.NONE, null,
                List.of(UUID.randomUUID(), UUID.randomUUID()));
        assertThat(once.isRotationValid()).isFalse();
        assertThat(once.isDueDateValid()).isTrue();
    }

    @Test
    void recurringTaskNeedsDueDate() {
        assertThat(new TaskRequest("Basura", null, null, TaskRecurrence.DAILY, null, null).isDueDateValid()).isFalse();
    }

    @Test
    void dueDateMustBeWithinYears1To9999() {
        assertThat(invalidFields(withDue(LocalDate.of(1, 1, 1)))).isEmpty();
        assertThat(invalidFields(withDue(LocalDate.of(9999, 12, 31)))).isEmpty();
        assertThat(invalidFields(withDue(LocalDate.of(0, 12, 31)))).containsExactly("dueDateInRange");
        assertThat(invalidFields(withDue(LocalDate.of(10000, 1, 1)))).containsExactly("dueDateInRange");
        assertThat(invalidFields(new TaskRequest("x", null, null, TaskRecurrence.NONE, null, null))).isEmpty();
    }

    private static TaskRequest withDue(LocalDate due) {
        return new TaskRequest("x", null, null, TaskRecurrence.DAILY, due, null);
    }
}
