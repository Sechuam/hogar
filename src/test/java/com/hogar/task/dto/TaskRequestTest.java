package com.hogar.task.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.task.domain.TaskRecurrence;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskRequestTest {

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
}
