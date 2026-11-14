package com.hogar.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TaskTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final LocalDate DUE = LocalDate.of(2026, 3, 31);
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CARLA = UUID.randomUUID();

    @Test
    void createsPendingTaskWithAnchorEqualToDueDate() {
        Task task = Task.create(HOUSEHOLD, ANA, "  Sacar la basura ", "  Los martes  ", BOB,
                TaskRecurrence.WEEKLY, DUE, null, NOW);

        assertThat(task.getId()).isNotNull();
        assertThat(task.getHouseholdId()).isEqualTo(HOUSEHOLD);
        assertThat(task.getCreatedBy()).isEqualTo(ANA);
        assertThat(task.getTitle()).isEqualTo("Sacar la basura");
        assertThat(task.getDescription()).isEqualTo("Los martes");
        assertThat(task.getAssigneeId()).isEqualTo(BOB);
        assertThat(task.getRecurrence()).isEqualTo(TaskRecurrence.WEEKLY);
        assertThat(task.isRecurring()).isTrue();
        assertThat(task.getDueDate()).isEqualTo(DUE);
        assertThat(task.getAnchorDate()).isEqualTo(DUE);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getRotation()).isEmpty();
        assertThat(task.getLastCompletedAt()).isNull();
        assertThat(task.getCreatedAt()).isEqualTo(NOW);
        assertThat(task.getUpdatedAt()).isEqualTo(NOW);
        assertThat(task.getVersion()).isNull();
    }

    @Test
    void oneOffTaskMayHaveNoDateNorAssigneeAndBlankDescriptionIsNull() {
        Task task = Task.create(HOUSEHOLD, ANA, "Arreglar grifo", "   ", null, TaskRecurrence.NONE, null,
                List.of(), NOW);

        assertThat(task.getDescription()).isNull();
        assertThat(task.getAssigneeId()).isNull();
        assertThat(task.getDueDate()).isNull();
        assertThat(task.getAnchorDate()).isNull();
        assertThat(task.isRecurring()).isFalse();
    }

    @Test
    void rotationDefaultsAssigneeToFirstMember() {
        Task task = weeklyWithRotation(null, BOB, ANA, CARLA);

        assertThat(task.getRotation()).containsExactly(BOB, ANA, CARLA);
        assertThat(task.getAssigneeId()).isEqualTo(BOB);
        assertThatThrownBy(() -> task.getRotation().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void explicitAssigneeMustBeInTheRotation() {
        assertThat(weeklyWithRotation(ANA, BOB, ANA).getAssigneeId()).isEqualTo(ANA);
        assertThatThrownBy(() -> weeklyWithRotation(CARLA, BOB, ANA)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rotationIsCopied() {
        List<UUID> members = new ArrayList<>(List.of(ANA, BOB));
        Task task = Task.create(HOUSEHOLD, ANA, "Baño", null, null, TaskRecurrence.WEEKLY, DUE, members, NOW);

        members.add(CARLA);

        assertThat(task.getRotation()).containsExactly(ANA, BOB);
    }

    @Test
    void rejectsInvalidData() {
        assertInvalid(() -> create(null, TaskRecurrence.NONE, null, List.of()));
        assertInvalid(() -> create("   ", TaskRecurrence.NONE, null, List.of()));
        assertInvalid(() -> create("x".repeat(101), TaskRecurrence.NONE, null, List.of()));
        assertInvalid(() -> Task.create(HOUSEHOLD, ANA, "Baño", "x".repeat(1001), null, TaskRecurrence.NONE, null,
                List.of(), NOW));
        // Recurrente sin fecha.
        assertInvalid(() -> create("Baño", TaskRecurrence.DAILY, null, List.of()));
        // Rotación en tarea puntual, con un solo miembro, demasiado larga o con repetidos.
        assertInvalid(() -> create("Baño", TaskRecurrence.NONE, DUE, List.of(ANA, BOB)));
        assertInvalid(() -> create("Baño", TaskRecurrence.WEEKLY, DUE, List.of(ANA)));
        assertInvalid(() -> create("Baño", TaskRecurrence.WEEKLY, DUE,
                IntStream.range(0, 21).mapToObj(i -> UUID.randomUUID()).toList()));
        assertInvalid(() -> create("Baño", TaskRecurrence.WEEKLY, DUE, List.of(ANA, BOB, ANA)));
        assertThatThrownBy(() -> create("Baño", TaskRecurrence.WEEKLY, DUE, Arrays.asList(ANA, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> create("Baño", null, DUE, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Task.create(null, ANA, "Baño", null, null, TaskRecurrence.NONE, null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Task.create(HOUSEHOLD, null, "Baño", null, null, TaskRecurrence.NONE, null, null,
                NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Task.create(HOUSEHOLD, ANA, "Baño", null, null, TaskRecurrence.NONE, null, null,
                null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void acceptsTheMaximumRotationAndTitleLength() {
        List<UUID> twenty = IntStream.range(0, 20).mapToObj(i -> UUID.randomUUID()).toList();

        Task task = create("x".repeat(100), TaskRecurrence.WEEKLY, DUE, twenty);

        assertThat(task.getRotation()).containsExactlyElementsOf(twenty);
        assertThat(task.getTitle()).hasSize(100);
    }

    @Test
    void updateKeepsAnchorWhenScheduleDoesNotChange() {
        Task task = Task.create(HOUSEHOLD, ANA, "Baño", null, ANA, TaskRecurrence.MONTHLY, DUE, null, NOW);
        task.advanceTo(LocalDate.of(2026, 4, 30), ANA, NOW);

        task.update("Baño grande", "con lejía", BOB, TaskRecurrence.MONTHLY, LocalDate.of(2026, 4, 30), null, LATER);

        assertThat(task.getTitle()).isEqualTo("Baño grande");
        assertThat(task.getDescription()).isEqualTo("con lejía");
        assertThat(task.getAssigneeId()).isEqualTo(BOB);
        assertThat(task.getAnchorDate()).isEqualTo(DUE);
        assertThat(task.getUpdatedAt()).isEqualTo(LATER);
        assertThat(task.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void updateResetsAnchorWhenDueDateChanges() {
        Task task = Task.create(HOUSEHOLD, ANA, "Baño", null, null, TaskRecurrence.MONTHLY, DUE, null, NOW);
        task.advanceTo(LocalDate.of(2026, 4, 30), null, NOW);

        task.update("Baño", null, null, TaskRecurrence.MONTHLY, LocalDate.of(2026, 5, 15), null, LATER);

        assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2026, 5, 15));
        assertThat(task.getAnchorDate()).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    @Test
    void updateResetsAnchorWhenRecurrenceChanges() {
        Task task = Task.create(HOUSEHOLD, ANA, "Baño", null, null, TaskRecurrence.MONTHLY, DUE, null, NOW);
        task.advanceTo(LocalDate.of(2026, 4, 30), null, NOW);

        task.update("Baño", null, null, TaskRecurrence.WEEKLY, LocalDate.of(2026, 4, 30), null, LATER);

        assertThat(task.getRecurrence()).isEqualTo(TaskRecurrence.WEEKLY);
        assertThat(task.getAnchorDate()).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void updateCanChangeOrRemoveTheRotation() {
        Task task = weeklyWithRotation(null, ANA, BOB, CARLA);

        task.update("Baño", null, CARLA, TaskRecurrence.WEEKLY, DUE, List.of(CARLA, ANA), LATER);
        assertThat(task.getRotation()).containsExactly(CARLA, ANA);
        assertThat(task.getAssigneeId()).isEqualTo(CARLA);

        task.update("Baño", null, BOB, TaskRecurrence.WEEKLY, DUE, List.of(ANA, BOB, CARLA), LATER);
        assertThat(task.getRotation()).containsExactly(ANA, BOB, CARLA);

        task.update("Baño", null, null, TaskRecurrence.NONE, DUE, List.of(), LATER);
        assertThat(task.getRotation()).isEmpty();
        assertThat(task.getAssigneeId()).isNull();
    }

    @Test
    void invalidUpdateLeavesTheTaskUntouched() {
        Task task = weeklyWithRotation(null, ANA, BOB);

        assertInvalid(() -> task.update("Otro", null, CARLA, TaskRecurrence.WEEKLY, DUE, List.of(ANA, BOB), LATER));
        assertInvalid(() -> task.update("Otro", "x".repeat(1001), null, TaskRecurrence.DAILY, DUE.plusDays(1),
                List.of(BOB, CARLA), LATER));
        assertThatThrownBy(() -> task.update("Otro", null, null, TaskRecurrence.WEEKLY, DUE, null, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(task.getTitle()).isEqualTo("Baño");
        assertThat(task.getAssigneeId()).isEqualTo(ANA);
        assertThat(task.getRecurrence()).isEqualTo(TaskRecurrence.WEEKLY);
        assertThat(task.getRotation()).containsExactly(ANA, BOB);
        assertThat(task.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void doneTaskCannotBeEditedUntilReopened() {
        Task task = create("Grifo", TaskRecurrence.NONE, null, List.of());
        task.markDone(NOW);

        assertThatThrownBy(() -> task.update("Grifo", null, null, TaskRecurrence.NONE, null, null, LATER))
                .isInstanceOf(IllegalStateException.class);

        task.reopen(LATER);
        task.update("Grifo de la cocina", null, null, TaskRecurrence.NONE, null, null, LATER);
        assertThat(task.getTitle()).isEqualTo("Grifo de la cocina");
    }

    @Test
    void recordCompletionSnapshotsCurrentOccurrenceAndAssignee() {
        Task task = weeklyWithRotation(BOB, ANA, BOB);

        TaskCompletion completion = task.recordCompletion(CARLA, LATER);

        assertThat(completion.getId()).isNotNull();
        assertThat(completion.isNew()).isTrue();
        assertThat(completion.getTaskId()).isEqualTo(task.getId());
        assertThat(completion.getCompletedBy()).isEqualTo(CARLA);
        assertThat(completion.getAssigneeId()).isEqualTo(BOB);
        assertThat(completion.getDueDate()).isEqualTo(DUE);
        assertThat(completion.getCompletedAt()).isEqualTo(LATER);
        assertThat(task.getLastCompletedAt()).isEqualTo(LATER);
        assertThat(task.getUpdatedAt()).isEqualTo(LATER);
        // Registrar no cambia estado, fecha ni asignado.
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getDueDate()).isEqualTo(DUE);
        assertThat(task.getAssigneeId()).isEqualTo(BOB);
    }

    @Test
    void recordCompletionRequiresCompleter() {
        Task task = create("Grifo", TaskRecurrence.NONE, null, List.of());

        assertThatThrownBy(() -> task.recordCompletion(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThat(task.getLastCompletedAt()).isNull();
    }

    @Test
    void oneOffLifecycle() {
        Task task = create("Grifo", TaskRecurrence.NONE, DUE, List.of());

        task.recordCompletion(ANA, LATER);
        task.markDone(LATER);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThatThrownBy(() -> task.recordCompletion(ANA, LATER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.markDone(LATER)).isInstanceOf(IllegalStateException.class);

        task.reopen(LATER.plusSeconds(1));
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getLastCompletedAt()).isEqualTo(LATER);
        assertThat(task.getUpdatedAt()).isEqualTo(LATER.plusSeconds(1));
        assertThatThrownBy(() -> task.reopen(LATER)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = TaskRecurrence.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void recurringTasksNeverBecomeDone(TaskRecurrence recurrence) {
        Task task = create("Baño", recurrence, DUE, List.of());

        assertThatThrownBy(() -> task.markDone(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.reopen(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void advanceMovesDueDateAndRotatesKeepingAnchor() {
        Task task = weeklyWithRotation(null, ANA, BOB);

        task.advanceTo(DUE.plusWeeks(1), BOB, LATER);

        assertThat(task.getDueDate()).isEqualTo(DUE.plusWeeks(1));
        assertThat(task.getAnchorDate()).isEqualTo(DUE);
        assertThat(task.getAssigneeId()).isEqualTo(BOB);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getUpdatedAt()).isEqualTo(LATER);

        // Ningún miembro activo en la rotación: queda sin asignar.
        task.advanceTo(DUE.plusWeeks(2), null, LATER);
        assertThat(task.getAssigneeId()).isNull();
    }

    @Test
    void advanceRejectsInvalidTargets() {
        Task rotating = weeklyWithRotation(null, ANA, BOB);
        assertInvalid(() -> rotating.advanceTo(DUE, BOB, LATER));
        assertInvalid(() -> rotating.advanceTo(DUE.minusDays(1), BOB, LATER));
        assertInvalid(() -> rotating.advanceTo(DUE.plusWeeks(1), CARLA, LATER));
        assertThatThrownBy(() -> rotating.advanceTo(null, BOB, LATER)).isInstanceOf(NullPointerException.class);

        Task fixed = Task.create(HOUSEHOLD, ANA, "Baño", null, ANA, TaskRecurrence.WEEKLY, DUE, null, NOW);
        assertInvalid(() -> fixed.advanceTo(DUE.plusWeeks(1), BOB, LATER));
        assertInvalid(() -> fixed.advanceTo(DUE.plusWeeks(1), null, LATER));
        fixed.advanceTo(DUE.plusWeeks(1), ANA, LATER);
        assertThat(fixed.getAssigneeId()).isEqualTo(ANA);

        Task oneOff = create("Grifo", TaskRecurrence.NONE, DUE, List.of());
        assertThatThrownBy(() -> oneOff.advanceTo(DUE.plusDays(1), null, LATER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void overdueOnlyWhenPendingWithPastDueDate() {
        LocalDate today = DUE;
        assertThat(create("a", TaskRecurrence.NONE, DUE.minusDays(1), List.of()).isOverdue(today)).isTrue();
        assertThat(create("a", TaskRecurrence.NONE, DUE, List.of()).isOverdue(today)).isFalse();
        assertThat(create("a", TaskRecurrence.NONE, null, List.of()).isOverdue(today)).isFalse();

        Task done = create("a", TaskRecurrence.NONE, DUE.minusDays(1), List.of());
        done.markDone(NOW);
        assertThat(done.isOverdue(today)).isFalse();
    }

    @Test
    void equalityById() {
        Task task = create("a", TaskRecurrence.NONE, null, List.of());
        Task other = create("a", TaskRecurrence.NONE, null, List.of());
        TaskCompletion completion = task.recordCompletion(ANA, NOW);

        assertThat(task).isEqualTo(task).isNotEqualTo(other).isNotEqualTo("a").hasSameHashCodeAs(task);
        assertThat(task.hashCode()).isEqualTo(task.getId().hashCode());
        assertThat(task.toString()).contains(task.getId().toString());
        assertThat(completion).isEqualTo(completion).isNotEqualTo(other.recordCompletion(ANA, NOW))
                .isNotEqualTo(task);
        assertThat(completion.hashCode()).isEqualTo(completion.getId().hashCode());
        assertThat(completion.toString()).contains(completion.getId().toString());
    }

    private static Task create(String title, TaskRecurrence recurrence, LocalDate due, List<UUID> rotation) {
        return Task.create(HOUSEHOLD, ANA, title, null, null, recurrence, due, rotation, NOW);
    }

    private static Task weeklyWithRotation(UUID assignee, UUID... rotation) {
        return Task.create(HOUSEHOLD, ANA, "Baño", null, assignee, TaskRecurrence.WEEKLY, DUE, List.of(rotation), NOW);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class);
    }
}
