package com.hogar.task.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.household.domain.Household;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.task.domain.Task;
import com.hogar.task.domain.TaskCompletion;
import com.hogar.task.domain.TaskRecurrence;
import com.hogar.task.domain.TaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class TaskRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TaskCompletionRepository completions;

    @Autowired
    private HouseholdRepository households;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    private UUID householdId;
    private UUID otherHouseholdId;
    private UUID ana;
    private UUID bob;
    private UUID carla;

    @BeforeEach
    void setUp() {
        householdId = households.saveAndFlush(Household.create("Piso", null, null, NOW)).getId();
        otherHouseholdId = households.saveAndFlush(Household.create("Otro", null, null, NOW)).getId();
        ana = users().create("Ana").id();
        bob = users().create("Bob").id();
        carla = users().create("Carla").id();
    }

    @Test
    void persistsAndLoadsWithRotationInOrder() {
        Task created = tasks.saveAndFlush(Task.create(householdId, ana, "Baño", "Con lejía", null,
                TaskRecurrence.MONTHLY, LocalDate.of(2026, 1, 31), List.of(carla, ana, bob), NOW));
        em.clear();

        Task loaded = tasks.findByIdAndHouseholdId(created.getId(), householdId).orElseThrow();

        assertThat(persistenceUnitUtil().isLoaded(loaded, "rotation")).isTrue();
        assertThat(loaded.getRotation()).containsExactly(carla, ana, bob);
        assertThat(loaded.getAssigneeId()).isEqualTo(carla);
        assertThat(loaded.getTitle()).isEqualTo("Baño");
        assertThat(loaded.getDescription()).isEqualTo("Con lejía");
        assertThat(loaded.getRecurrence()).isEqualTo(TaskRecurrence.MONTHLY);
        assertThat(loaded.getDueDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(loaded.getAnchorDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(loaded.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(loaded.getCreatedBy()).isEqualTo(ana);
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getLastCompletedAt()).isNull();
        assertThat(loaded.getVersion()).isZero();
        assertThat(tasks.findByIdAndHouseholdId(created.getId(), otherHouseholdId)).isEmpty();
        assertThat(rows("select count(*) from task_rotation_members where task_id = ?", created.getId()))
                .isEqualTo(3);
    }

    @Test
    void findForUpdateLocksTheRootAndLoadsTheRotationLazily() {
        Task created = tasks.saveAndFlush(weekly(householdId, "Cocina", TODAY, List.of(ana, bob)));
        em.clear();

        Task locked = tasks.findForUpdate(created.getId(), householdId).orElseThrow();

        assertThat(persistenceUnitUtil().isLoaded(locked, "rotation")).isFalse();
        assertThat(locked.getRotation()).containsExactly(ana, bob);
        assertThat(tasks.findForUpdate(created.getId(), otherHouseholdId)).isEmpty();
    }

    @Test
    void reorderingAndShrinkingTheRotationRespectsUniquenessAtCommit() {
        Task task = tasks.saveAndFlush(weekly(householdId, "Cocina", TODAY, List.of(ana, bob, carla)));

        // Intercambio: a mitad de flush la posición 0 y la 1 tendrían el mismo usuario.
        task.update("Cocina", null, null, TaskRecurrence.WEEKLY, TODAY, List.of(bob, ana, carla), NOW.plusSeconds(1));
        em.flush();
        checkDeferredConstraintsNow();
        task.update("Cocina", null, carla, TaskRecurrence.WEEKLY, TODAY, List.of(carla, bob), NOW.plusSeconds(2));
        em.flush();
        checkDeferredConstraintsNow();
        em.clear();

        Task reloaded = tasks.findByIdAndHouseholdId(task.getId(), householdId).orElseThrow();
        assertThat(reloaded.getRotation()).containsExactly(carla, bob);
        assertThat(reloaded.getAssigneeId()).isEqualTo(carla);
        assertThat(reloaded.getVersion()).isEqualTo(2);
        assertThat(rows("select count(*) from task_rotation_members where task_id = ?", task.getId())).isEqualTo(2);

        reloaded.update("Cocina", null, null, TaskRecurrence.NONE, TODAY, List.of(), NOW.plusSeconds(3));
        em.flush();
        assertThat(rows("select count(*) from task_rotation_members where task_id = ?", task.getId())).isZero();
    }

    @Test
    void oneRotationEntryPerUserAndTask() {
        Task task = tasks.saveAndFlush(weekly(householdId, "Cocina", TODAY, List.of(ana, bob)));

        jdbc.update("insert into task_rotation_members (task_id, position, user_id) values (?, 2, ?)",
                task.getId(), ana);

        // La restricción es diferida: se comprueba al hacer commit o al pedirla inmediata.
        assertThatThrownBy(this::checkDeferredConstraintsNow).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsCompletionsAndPagesThemMostRecentFirst() {
        Task task = tasks.saveAndFlush(weekly(householdId, "Cocina", TODAY, List.of(ana, bob)));
        TaskCompletion first = completions.save(task.recordCompletion(bob, NOW));
        task.advanceTo(TODAY.plusWeeks(1), bob, NOW);
        TaskCompletion second = completions.save(task.recordCompletion(ana, NOW.plusSeconds(60)));
        task.advanceTo(TODAY.plusWeeks(2), ana, NOW.plusSeconds(60));
        // Misma hora: desempata el id (desc).
        TaskCompletion third = completions.save(task.recordCompletion(ana, NOW.plusSeconds(60)));
        tasks.flush();
        em.clear();

        Page<TaskCompletion> page0 = completions.findByTaskIdOrderByCompletedAtDescIdDesc(task.getId(),
                PageRequest.of(0, 2));
        Page<TaskCompletion> page1 = completions.findByTaskIdOrderByCompletedAtDescIdDesc(task.getId(),
                PageRequest.of(1, 2));

        TaskCompletion[] sameInstant = second.getId().toString().compareTo(third.getId().toString()) > 0
                ? new TaskCompletion[] {second, third} : new TaskCompletion[] {third, second};
        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.getContent()).containsExactly(sameInstant);
        assertThat(page1.getContent()).containsExactly(first);
        TaskCompletion loadedFirst = page1.getContent().getFirst();
        assertThat(loadedFirst.isNew()).isFalse();
        assertThat(loadedFirst.getTaskId()).isEqualTo(task.getId());
        assertThat(loadedFirst.getCompletedBy()).isEqualTo(bob);
        assertThat(loadedFirst.getAssigneeId()).isEqualTo(ana);
        assertThat(loadedFirst.getDueDate()).isEqualTo(TODAY);
        assertThat(loadedFirst.getCompletedAt()).isEqualTo(NOW);

        Task reloaded = tasks.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getLastCompletedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(reloaded.getDueDate()).isEqualTo(TODAY.plusWeeks(2));
        assertThat(reloaded.getAnchorDate()).isEqualTo(TODAY);
    }

    @Test
    void deletingTheTaskCascadesToRotationAndCompletions() {
        Task viaJpa = tasks.saveAndFlush(weekly(householdId, "A", TODAY, List.of(ana, bob)));
        Task viaSql = tasks.saveAndFlush(weekly(householdId, "B", TODAY, List.of(ana, bob)));
        completions.saveAndFlush(viaJpa.recordCompletion(ana, NOW));
        completions.saveAndFlush(viaSql.recordCompletion(ana, NOW));
        tasks.flush();
        em.clear();

        tasks.delete(tasks.findById(viaJpa.getId()).orElseThrow());
        tasks.flush();
        jdbc.update("delete from tasks where id = ?", viaSql.getId());

        for (UUID id : List.of(viaJpa.getId(), viaSql.getId())) {
            assertThat(rows("select count(*) from task_rotation_members where task_id = ?", id)).isZero();
            assertThat(rows("select count(*) from task_completions where task_id = ?", id)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update tasks set recurrence = 'YEARLY' where id = ?",
            "update tasks set status = 'CANCELLED' where id = ?",
            "update tasks set title = '   ' where id = ?",
            // Recurrente sin fecha o sin ancla.
            "update tasks set due_date = null where id = ?",
            "update tasks set anchor_date = null where id = ?",
            // Recurrente nunca DONE.
            "update tasks set status = 'DONE' where id = ?",
            "update tasks set anchor_date = due_date + 1 where id = ?",
            "update tasks set assignee_id = gen_random_uuid() where id = ?",
            "update task_rotation_members set position = -1 where task_id = ? and position = 1",
            "update task_rotation_members set user_id = gen_random_uuid() where task_id = ?",
            "insert into task_completions (id, task_id, completed_by, completed_at) values (gen_random_uuid(), ?, gen_random_uuid(), now())"
    })
    void databaseChecksRejectInvalidRows(String sql) {
        Task task = tasks.saveAndFlush(weekly(householdId, "Cocina", TODAY, List.of(ana, bob)));

        assertThatThrownBy(() -> jdbc.update(sql, task.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneOffTaskWithoutDateIsValidButAnchorFollowsDueDate() {
        Task task = tasks.saveAndFlush(oneOff(householdId, "Grifo", null, null));
        task.markDone(NOW);
        tasks.flush();
        assertThat(jdbc.queryForObject("select status from tasks where id = ?", String.class, task.getId()))
                .isEqualTo("DONE");

        assertThatThrownBy(() -> jdbc.update("update tasks set anchor_date = date '2026-01-01' where id = ?",
                task.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(TaskRecurrence.class)
    void recurrenceCheckAcceptsEveryEnumValue(TaskRecurrence recurrence) {
        Task task = tasks.saveAndFlush(weekly(householdId, "Cocina", TODAY, List.of()));

        assertThat(jdbc.update("update tasks set recurrence = ? where id = ?", recurrence.name(), task.getId()))
                .isEqualTo(1);
    }

    @Test
    void searchOrdersByDueDateNullsLastThenCreationAndPaginates() {
        Task noDateEarly = tasks.save(oneOff(householdId, "Sin fecha 1", null, null));
        Task march20 = tasks.save(oneOff(householdId, "20 marzo", LocalDate.of(2026, 3, 20), ana));
        Task march5 = tasks.save(oneOff(householdId, "5 marzo", LocalDate.of(2026, 3, 5), bob));
        Task march20Later = tasks.save(Task.create(householdId, ana, "20 marzo bis", null, null, TaskRecurrence.NONE,
                LocalDate.of(2026, 3, 20), null, NOW.plusSeconds(60)));
        Task noDateLater = tasks.save(Task.create(householdId, ana, "Sin fecha 2", null, null, TaskRecurrence.NONE,
                null, null, NOW.plusSeconds(60)));
        tasks.save(oneOff(otherHouseholdId, "Otro hogar", LocalDate.of(2026, 3, 1), ana));
        tasks.flush();
        em.clear();

        // El orden pedido en el Pageable se ignora: lo fija el contrato.
        Page<Task> page0 = tasks.search(householdId, null, null, null, TODAY,
                PageRequest.of(0, 3, Sort.by(Sort.Order.desc("title"))));
        Page<Task> page1 = tasks.search(householdId, null, null, null, TODAY, PageRequest.of(1, 3));

        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getContent()).containsExactly(march5, march20, march20Later);
        assertThat(page1.getContent()).containsExactly(noDateEarly, noDateLater);
        assertThat(tasks.search(householdId, null, null, null, null, Pageable.unpaged()).getContent())
                .containsExactly(march5, march20, march20Later, noDateEarly, noDateLater);
    }

    @Test
    void searchFiltersByAssigneeStatusAndOverdue() {
        Task overdue = tasks.save(oneOff(householdId, "Vencida", TODAY.minusDays(1), ana));
        Task dueToday = tasks.save(oneOff(householdId, "Hoy", TODAY, bob));
        Task noDate = tasks.save(oneOff(householdId, "Sin fecha", null, ana));
        Task doneInThePast = oneOff(householdId, "Hecha", TODAY.minusDays(5), ana);
        doneInThePast.markDone(NOW);
        tasks.save(doneInThePast);
        Task recurringOverdue = tasks.save(weekly(householdId, "Semanal", TODAY.minusDays(2), List.of(bob, ana)));
        tasks.save(oneOff(otherHouseholdId, "Otro hogar", TODAY.minusDays(1), ana));
        tasks.flush();
        em.clear();

        assertThat(search(null, null, true)).containsExactly(recurringOverdue, overdue);
        assertThat(search(null, null, false)).containsExactly(doneInThePast, dueToday, noDate);
        assertThat(search(ana, null, null)).containsExactly(doneInThePast, overdue, noDate);
        assertThat(search(ana, null, true)).containsExactly(overdue);
        assertThat(search(bob, null, false)).containsExactly(dueToday);
        assertThat(search(null, TaskStatus.DONE, null)).containsExactly(doneInThePast);
        assertThat(search(null, TaskStatus.PENDING, false)).containsExactly(dueToday, noDate);
        assertThat(search(null, TaskStatus.DONE, true)).isEmpty();
        assertThat(search(carla, null, null)).isEmpty();
    }

    @Test
    void rotationsOfAPageAreLoadedInBatchOnFirstAccess() {
        for (int i = 0; i < 3; i++) {
            tasks.save(weekly(householdId, "T" + i, TODAY.plusDays(i), List.of(ana, bob)));
        }
        tasks.flush();
        em.clear();

        List<Task> page = tasks.search(householdId, null, null, null, TODAY, PageRequest.of(0, 10)).getContent();
        assertThat(page).hasSize(3)
                .allSatisfy(t -> assertThat(persistenceUnitUtil().isLoaded(t, "rotation")).isFalse());

        assertThat(page.getFirst().getRotation()).containsExactly(ana, bob);

        // @BatchSize: la primera colección accedida inicializa las de toda la página en la misma consulta.
        assertThat(page).allSatisfy(t -> assertThat(persistenceUnitUtil().isLoaded(t, "rotation")).isTrue());
    }

    private List<Task> search(UUID assignee, TaskStatus status, Boolean overdue) {
        return tasks.search(householdId, assignee, status, overdue, TODAY, PageRequest.of(0, 20)).getContent();
    }

    private Task oneOff(UUID household, String title, LocalDate due, UUID assignee) {
        return Task.create(household, ana, title, null, assignee, TaskRecurrence.NONE, due, null, NOW);
    }

    private Task weekly(UUID household, String title, LocalDate due, List<UUID> rotation) {
        return Task.create(household, ana, title, null, null, TaskRecurrence.WEEKLY, due, rotation, NOW);
    }

    /**
     * Evalúa ya la unicidad diferida (en un test @Transactional el commit nunca llega) y la vuelve a
     * diferir: {@code SET CONSTRAINTS} dura hasta el final de la transacción.
     */
    private void checkDeferredConstraintsNow() {
        jdbc.execute("set constraints uk_task_rotation_members_task_user immediate");
        jdbc.execute("set constraints uk_task_rotation_members_task_user deferred");
    }

    private int rows(String sql, UUID id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private PersistenceUnitUtil persistenceUnitUtil() {
        return em.getEntityManagerFactory().getPersistenceUnitUtil();
    }
}
