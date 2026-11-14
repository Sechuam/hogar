package com.hogar.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.ActiveMember;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.shared.error.BadRequestException;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.ConflictException;
import com.hogar.shared.error.DomainException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import com.hogar.shared.web.PageResponse;
import com.hogar.task.domain.Task;
import com.hogar.task.domain.TaskCompletion;
import com.hogar.task.domain.TaskRecurrence;
import com.hogar.task.domain.TaskStatus;
import com.hogar.task.dto.CompleteTaskRequest;
import com.hogar.task.dto.TaskCompletionResponse;
import com.hogar.task.dto.TaskRequest;
import com.hogar.task.dto.TaskResponse;
import com.hogar.task.repository.TaskCompletionRepository;
import com.hogar.task.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    @Mock
    private TaskRepository tasks;
    @Mock
    private TaskCompletionRepository completions;
    @Mock
    private MembershipGuard guard;
    @Mock
    private HouseholdQueries households;

    private TaskService service;
    private final UUID householdId = UUID.randomUUID();
    /** OWNER. */
    private final UUID ana = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();
    private final UUID eva = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TaskService(tasks, completions, guard, households, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(tasks.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(completions.save(any(TaskCompletion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void members(UUID... members) {
        List<ActiveMember> active = new ArrayList<>();
        for (int i = 0; i < members.length; i++) {
            active.add(new ActiveMember(members[i], i == 0 ? HouseholdRole.OWNER : HouseholdRole.MEMBER,
                    NOW.minusSeconds(3600 - i)));
        }
        lenient().when(households.getSplitInfo(householdId))
                .thenReturn(new HouseholdSplitInfo(householdId, SplitPolicy.EQUAL, "EUR", active));
    }

    private static TaskRequest request(UUID assignee, TaskRecurrence recurrence, LocalDate due, List<UUID> rotation) {
        return new TaskRequest("Fregar", "Platos", assignee, recurrence, due, rotation);
    }

    private Task existing(UUID creator, UUID assignee, TaskRecurrence recurrence, LocalDate due, List<UUID> rotation) {
        Task task = Task.create(householdId, creator, "Fregar", null, assignee, recurrence, due, rotation,
                NOW.minusSeconds(86_400));
        lenient().when(tasks.findForUpdate(task.getId(), householdId)).thenReturn(Optional.of(task));
        lenient().when(tasks.findByIdAndHouseholdId(task.getId(), householdId)).thenReturn(Optional.of(task));
        return task;
    }

    private static String code(Throwable e) {
        return ((DomainException) e).getCode();
    }

    // --- Alta ---

    @Test
    void createsTaskWithTheAuthenticatedUserAsCreator() {
        members(ana, luis);

        TaskResponse created = service.create(householdId, luis, request(ana, TaskRecurrence.NONE, TODAY, null));

        assertThat(created.createdBy()).isEqualTo(luis);
        assertThat(created.assigneeId()).isEqualTo(ana);
        assertThat(created.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(created.overdue()).isFalse();
        assertThat(created.rotation()).isEmpty();
        assertThat(created.createdAt()).isEqualTo(NOW);
        verify(tasks).save(any(Task.class));
    }

    @Test
    void createWithRotationAndNoAssigneeAssignsTheFirstOfTheRotation() {
        members(ana, luis, eva);

        TaskResponse created = service.create(householdId, ana,
                request(null, TaskRecurrence.WEEKLY, TODAY, List.of(eva, luis)));

        assertThat(created.assigneeId()).isEqualTo(eva);
        assertThat(created.rotation()).containsExactly(eva, luis);
    }

    @Test
    void createWithAPastDueDateIsOverdue() {
        members(ana);

        TaskResponse created = service.create(householdId, ana,
                request(null, TaskRecurrence.NONE, TODAY.minusDays(1), null));

        assertThat(created.overdue()).isTrue();
    }

    @Test
    void createChecksMembershipBeforeAnythingElse() {
        doThrow(new NotFoundException("household-not-found", "x")).when(guard).requireMember(householdId, outsider);

        assertThatThrownBy(() -> service.create(householdId, outsider, request(null, TaskRecurrence.NONE, null, null)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(tasks, households, completions);
    }

    @Test
    void assigneeMustBeAnActiveMember() {
        members(ana, luis);

        assertThatThrownBy(() -> service.create(householdId, ana, request(outsider, TaskRecurrence.NONE, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("assignee-not-member"));
        verify(tasks, never()).save(any());
    }

    @Test
    void everyRotationMemberMustBeActive() {
        members(ana, luis);

        assertThatThrownBy(() -> service.create(householdId, ana,
                request(null, TaskRecurrence.DAILY, TODAY, List.of(ana, outsider))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("rotation-member-not-member"));
    }

    @Test
    void assigneeInTheRotationWhoIsNoLongerAMemberIsReportedAsAssigneeNotMember() {
        members(ana, luis);

        assertThatThrownBy(() -> service.create(householdId, ana,
                request(outsider, TaskRecurrence.DAILY, TODAY, List.of(ana, outsider))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("assignee-not-member"));
    }

    @Test
    void assigneeMustBelongToTheRotation() {
        members(ana, luis, eva);

        assertThatThrownBy(() -> service.create(householdId, ana,
                request(eva, TaskRecurrence.DAILY, TODAY, List.of(ana, luis))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("assignee-not-in-rotation"));
    }

    // --- Listado y detalle ---

    @Test
    void listWithMineFiltersByTheAuthenticatedUser() {
        Task task = existing(ana, luis, TaskRecurrence.NONE, TODAY.minusDays(2), null);
        when(tasks.search(householdId, luis, TaskStatus.PENDING, true, TODAY, PageRequest.of(1, 5)))
                .thenReturn(new PageImpl<>(List.of(task), PageRequest.of(1, 5), 6));

        PageResponse<TaskResponse> page = service.list(householdId, luis, true, null, TaskStatus.PENDING, true, 1, 5);

        assertThat(page.content()).extracting(TaskResponse::id).containsExactly(task.getId());
        assertThat(page.content().getFirst().overdue()).isTrue();
        assertThat(page.totalElements()).isEqualTo(6);
        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    void listWithAssigneeIdAndMineFalseFiltersByThatAssignee() {
        when(tasks.search(householdId, eva, null, null, TODAY, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(service.list(householdId, luis, false, eva, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void mineAndAssigneeIdTogetherAreConflictingFilters() {
        assertThatThrownBy(() -> service.list(householdId, luis, true, eva, null, null, 0, 20))
                .isInstanceOf(BadRequestException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("conflicting-filters"));
        verify(guard).requireMember(householdId, luis);
        verifyNoInteractions(tasks);
    }

    @Test
    void getReturnsTheTaskOrNotFound() {
        Task task = existing(ana, null, TaskRecurrence.NONE, null, null);

        assertThat(service.get(householdId, ana, task.getId()).id()).isEqualTo(task.getId());
        assertThatThrownBy(() -> service.get(householdId, ana, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-not-found"));
    }

    // --- Edición ---

    @Test
    void updateReplacesTheDataAndResetsTheAnchorWhenTheScheduleChanges() {
        members(ana, luis, eva);
        Task task = existing(ana, null, TaskRecurrence.DAILY, TODAY, null);

        TaskResponse updated = service.update(householdId, luis, task.getId(),
                request(eva, TaskRecurrence.WEEKLY, TODAY.plusDays(3), List.of(eva, luis)));

        assertThat(updated.recurrence()).isEqualTo(TaskRecurrence.WEEKLY);
        assertThat(updated.assigneeId()).isEqualTo(eva);
        assertThat(updated.rotation()).containsExactly(eva, luis);
        assertThat(task.getAnchorDate()).isEqualTo(TODAY.plusDays(3));
        assertThat(updated.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void updateLocksTheRowAfterCheckingMembership() {
        members(ana);
        Task task = existing(ana, null, TaskRecurrence.NONE, null, null);

        service.update(householdId, ana, task.getId(), request(null, TaskRecurrence.NONE, null, null));

        InOrder order = inOrder(guard, tasks);
        order.verify(guard).requireMember(householdId, ana);
        order.verify(tasks).findForUpdate(task.getId(), householdId);
    }

    @Test
    void updateOfADoneTaskIsAConflict() {
        Task task = existing(ana, null, TaskRecurrence.NONE, null, null);
        task.markDone(NOW);

        assertThatThrownBy(() -> service.update(householdId, ana, task.getId(),
                request(null, TaskRecurrence.NONE, null, null)))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-already-done"));
        verifyNoInteractions(households);
    }

    @Test
    void updateValidatesPeople() {
        members(ana, luis);
        Task task = existing(ana, null, TaskRecurrence.NONE, null, null);

        assertThatThrownBy(() -> service.update(householdId, ana, task.getId(),
                request(outsider, TaskRecurrence.NONE, null, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("assignee-not-member"));
        assertThat(task.getAssigneeId()).isNull();
    }

    @Test
    void updateOfAnUnknownTaskIsNotFound() {
        assertThatThrownBy(() -> service.update(householdId, ana, UUID.randomUUID(),
                request(null, TaskRecurrence.NONE, null, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-not-found"));
    }

    // --- Borrado ---

    @Test
    void creatorCanDelete() {
        members(ana, luis);
        Task task = existing(luis, null, TaskRecurrence.NONE, null, null);

        service.delete(householdId, luis, task.getId());

        verify(tasks).delete(task);
    }

    @Test
    void ownerCanDeleteSomeoneElsesTask() {
        members(ana, luis);
        Task task = existing(luis, null, TaskRecurrence.NONE, null, null);

        service.delete(householdId, ana, task.getId());

        verify(tasks).delete(task);
    }

    @Test
    void anotherMemberCannotDelete() {
        members(ana, luis, eva);
        Task task = existing(luis, eva, TaskRecurrence.NONE, null, null);

        assertThatThrownBy(() -> service.delete(householdId, eva, task.getId()))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-delete-forbidden"));
        verify(tasks, never()).delete(any(Task.class));
    }

    @Test
    void deleteOfAnUnknownTaskIsNotFound() {
        assertThatThrownBy(() -> service.delete(householdId, ana, UUID.randomUUID()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-not-found"));
    }

    // --- Compleción ---

    @Test
    void completingAOneOffTaskRecordsTheCompletionAndMarksItDone() {
        Task task = existing(ana, luis, TaskRecurrence.NONE, TODAY.minusDays(1), null);

        TaskResponse done = service.complete(householdId, eva, task.getId(), null);

        assertThat(done.status()).isEqualTo(TaskStatus.DONE);
        assertThat(done.overdue()).isFalse();
        assertThat(done.lastCompletedAt()).isEqualTo(NOW);
        ArgumentCaptor<TaskCompletion> saved = ArgumentCaptor.forClass(TaskCompletion.class);
        verify(completions).save(saved.capture());
        assertThat(saved.getValue().getCompletedBy()).isEqualTo(eva);
        assertThat(saved.getValue().getAssigneeId()).isEqualTo(luis);
        assertThat(saved.getValue().getDueDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(saved.getValue().getCompletedAt()).isEqualTo(NOW);
        verifyNoInteractions(households);
    }

    @Test
    void completingADoneTaskIsAConflict() {
        Task task = existing(ana, null, TaskRecurrence.NONE, null, null);
        task.markDone(NOW);

        assertThatThrownBy(() -> service.complete(householdId, ana, task.getId(), new CompleteTaskRequest(null)))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-already-done"));
        verify(completions, never()).save(any());
    }

    @Test
    void completingAnotherOccurrenceIsAMismatch() {
        Task task = existing(ana, null, TaskRecurrence.DAILY, TODAY, null);

        assertThatThrownBy(() -> service.complete(householdId, ana, task.getId(),
                new CompleteTaskRequest(TODAY.minusDays(1))))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-occurrence-mismatch"));
        assertThat(task.getDueDate()).isEqualTo(TODAY);
        verify(completions, never()).save(any());
    }

    @Test
    void occurrenceMismatchIsCheckedBeforeDoneState() {
        Task task = existing(ana, null, TaskRecurrence.NONE, TODAY, null);
        task.markDone(NOW);

        assertThatThrownBy(() -> service.complete(householdId, ana, task.getId(),
                new CompleteTaskRequest(TODAY.plusDays(1))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-occurrence-mismatch"));
    }

    @Test
    void completingWithTheCurrentOccurrenceSucceeds() {
        Task task = existing(ana, ana, TaskRecurrence.WEEKLY, TODAY, null);

        TaskResponse next = service.complete(householdId, ana, task.getId(), new CompleteTaskRequest(TODAY));

        assertThat(next.dueDate()).isEqualTo(TODAY.plusWeeks(1));
    }

    @Test
    void recurringWithoutRotationAdvancesAndKeepsTheAssignee() {
        Task task = existing(ana, luis, TaskRecurrence.DAILY, TODAY, null);

        TaskResponse next = service.complete(householdId, ana, task.getId(), null);

        assertThat(next.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(next.dueDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(next.assigneeId()).isEqualTo(luis);
        assertThat(task.getAnchorDate()).isEqualTo(TODAY);
        verify(completions).save(any(TaskCompletion.class));
        verifyNoInteractions(households);
    }

    @Test
    void lateCompletionJumpsToTheNextFutureOccurrence() {
        Task task = existing(ana, null, TaskRecurrence.WEEKLY, TODAY.minusDays(15), null);

        TaskResponse next = service.complete(householdId, ana, task.getId(), null);

        // Ocurrencias: -15, -8, -1, +6 → la primera posterior a hoy.
        assertThat(next.dueDate()).isEqualTo(TODAY.plusDays(6));
        assertThat(next.overdue()).isFalse();
    }

    @Test
    void monthlyFromThe31stGoesToTheLastDayOfTheNextMonth() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-31T08:00:00Z"), ZoneOffset.UTC);
        service = new TaskService(tasks, completions, guard, households, clock);
        Task task = existing(ana, null, TaskRecurrence.MONTHLY, LocalDate.of(2026, 1, 31), null);

        assertThat(service.complete(householdId, ana, task.getId(), null).dueDate())
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(service.complete(householdId, ana, task.getId(), null).dueDate())
                .isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void rotationPassesToTheNextMember() {
        members(ana, luis, eva);
        Task task = existing(ana, null, TaskRecurrence.DAILY, TODAY, List.of(ana, luis, eva));

        assertThat(service.complete(householdId, ana, task.getId(), null).assigneeId()).isEqualTo(luis);
        assertThat(service.complete(householdId, ana, task.getId(), null).assigneeId()).isEqualTo(eva);
        assertThat(service.complete(householdId, ana, task.getId(), null).assigneeId()).isEqualTo(ana);
    }

    @Test
    void rotationSkipsAMemberWhoLeftTheHousehold() {
        members(ana, eva); // Luis ya no es miembro activo
        Task task = existing(ana, ana, TaskRecurrence.DAILY, TODAY, List.of(ana, luis, eva));

        TaskResponse next = service.complete(householdId, eva, task.getId(), null);

        assertThat(next.assigneeId()).isEqualTo(eva);
        ArgumentCaptor<TaskCompletion> saved = ArgumentCaptor.forClass(TaskCompletion.class);
        verify(completions).save(saved.capture());
        assertThat(saved.getValue().getAssigneeId()).isEqualTo(ana);
    }

    @Test
    void rotationWithNobodyActiveLeavesTheTaskUnassigned() {
        members(eva); // ni Ana ni Luis siguen en el hogar
        Task task = existing(ana, ana, TaskRecurrence.DAILY, TODAY, List.of(ana, luis));

        TaskResponse next = service.complete(householdId, eva, task.getId(), null);

        assertThat(next.assigneeId()).isNull();
        assertThat(next.dueDate()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void completingChecksMembershipBeforeLocking() {
        doThrow(new NotFoundException("household-not-found", "x")).when(guard).requireMember(householdId, outsider);

        assertThatThrownBy(() -> service.complete(householdId, outsider, UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(tasks, completions);
    }

    @Test
    void completingAnUnknownTaskIsNotFound() {
        assertThatThrownBy(() -> service.complete(householdId, ana, UUID.randomUUID(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-not-found"));
    }

    // --- Reapertura ---

    @Test
    void reopenBringsADoneTaskBackToPending() {
        Task task = existing(ana, null, TaskRecurrence.NONE, null, null);
        task.markDone(NOW.minusSeconds(10));

        TaskResponse reopened = service.reopen(householdId, luis, task.getId());

        assertThat(reopened.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(reopened.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void reopenOfAPendingTaskIsAConflict() {
        Task task = existing(ana, null, TaskRecurrence.NONE, null, null);

        assertThatThrownBy(() -> service.reopen(householdId, ana, task.getId()))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-not-done"));
    }

    @Test
    void reopenOfARecurringTaskIsAConflict() {
        Task task = existing(ana, null, TaskRecurrence.DAILY, TODAY, null);

        assertThatThrownBy(() -> service.reopen(householdId, ana, task.getId()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-not-done"));
    }

    @Test
    void reopenOfAnUnknownTaskIsNotFound() {
        assertThatThrownBy(() -> service.reopen(householdId, ana, UUID.randomUUID()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-not-found"));
    }

    // --- Histórico ---

    @Test
    void completionsArePagedForTheTask() {
        Task task = existing(ana, luis, TaskRecurrence.DAILY, TODAY, null);
        TaskCompletion completion = task.recordCompletion(eva, NOW);
        when(completions.findByTaskIdOrderByCompletedAtDescIdDesc(eq(task.getId()), eq(PageRequest.of(0, 10))))
                .thenReturn(new PageImpl<>(List.of(completion), PageRequest.of(0, 10), 1));

        PageResponse<TaskCompletionResponse> page = service.completions(householdId, ana, task.getId(), 0, 10);

        assertThat(page.content()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(completion.getId());
            assertThat(c.taskId()).isEqualTo(task.getId());
            assertThat(c.completedBy()).isEqualTo(eva);
            assertThat(c.assigneeId()).isEqualTo(luis);
            assertThat(c.dueDate()).isEqualTo(TODAY);
            assertThat(c.completedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void completionsOfAnUnknownTaskAreNotFound() {
        assertThatThrownBy(() -> service.completions(householdId, ana, UUID.randomUUID(), 0, 10))
                .satisfies(e -> assertThat(code(e)).isEqualTo("task-not-found"));
        verifyNoInteractions(completions);
    }
}
