package com.hogar.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.shared.web.PageResponse;
import com.hogar.support.TestUsers.TestUser;
import com.hogar.task.domain.TaskRecurrence;
import com.hogar.task.dto.TaskCompletionResponse;
import com.hogar.task.dto.TaskResponse;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * QA Fase 3: el listado y el histórico de tareas paginan con un orden total (sin duplicar ni perder
 * filas aunque compartan fecha) y el listado no hace N+1 al cargar las rotaciones (D-040).
 */
class TaskListingIT extends TaskApiSupport {

    @Autowired
    private EntityManagerFactory emf;

    @Test
    void walkingEveryPageOfSameDayTasksReturnsEachExactlyOnceInContractOrder() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID h = household(ana, luis);
        LocalDate sameDay = today().plusDays(1);
        List<UUID> dated = new ArrayList<>();
        List<UUID> undated = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            dated.add(createTask(i % 2 == 0 ? ana : luis, h, oneOff("T" + i, null, sameDay)).id());
        }
        for (int i = 0; i < 6; i++) {
            undated.add(createTask(ana, h, oneOff("S" + i, null, null)).id());
        }
        UUID earlier = createTask(luis, h, oneOff("Antes", null, sameDay.minusDays(1))).id();

        List<TaskResponse> walked = new ArrayList<>();
        PageResponse<TaskResponse> page;
        int n = 0;
        do {
            page = listTasks(luis, h, "?size=5&page=" + n++);
            assertThat(page.totalElements()).isEqualTo(38);
            walked.addAll(page.content());
        } while (n < page.totalPages());

        assertThat(page.totalPages()).isEqualTo(8);
        List<UUID> ids = walked.stream().map(TaskResponse::id).toList();
        assertThat(ids).doesNotHaveDuplicates().hasSize(38);
        // dueDate asc, sin fecha al final; misma fecha → alta asc (id desempata).
        assertThat(ids.getFirst()).isEqualTo(earlier);
        assertThat(ids.subList(1, 32)).containsExactlyInAnyOrderElementsOf(dated);
        assertThat(ids.subList(32, 38)).containsExactlyInAnyOrderElementsOf(undated);
        Comparator<TaskResponse> contractOrder = Comparator
                .comparing(TaskResponse::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskResponse::createdAt);
        assertThat(walked).isSortedAccordingTo(contractOrder);

        List<UUID> again = new ArrayList<>();
        for (int p = 0; p < 8; p++) {
            again.addAll(listTasks(ana, h, "?size=5&page=" + p).content().stream().map(TaskResponse::id).toList());
        }
        assertThat(again).containsExactlyElementsOf(ids);
        assertThat(listTasks(ana, h, "?size=5&page=8").content()).isEmpty();
    }

    @Test
    void completionHistoryWithIdenticalInstantsPagesWithoutDuplicates() {
        TestUser ana = users().create("Ana");
        UUID h = household(ana);
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null, null));
        Timestamp sameInstant = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        List<UUID> inserted = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            UUID id = UUID.randomUUID();
            inserted.add(id);
            jdbc.update("insert into task_completions (id, task_id, completed_by, assignee_id, due_date, completed_at)"
                    + " values (?, ?, ?, null, ?, ?)", id, task.id(), ana.id(), today().minusDays(i), sameInstant);
        }
        complete(ana, h, task.id());

        List<TaskCompletionResponse> walked = new ArrayList<>();
        for (int p = 0; p < 5; p++) {
            PageResponse<TaskCompletionResponse> page = listCompletions(ana, h, task.id(), "?size=5&page=" + p);
            assertThat(page.totalElements()).isEqualTo(24);
            walked.addAll(page.content());
        }
        assertThat(walked).extracting(TaskCompletionResponse::id).doesNotHaveDuplicates().hasSize(24);
        // La compleción real (más reciente) primero; las simultáneas, por id desc (orden de uuid en
        // PostgreSQL = orden lexicográfico del texto, no UUID.compareTo).
        assertThat(walked.getFirst().completedAt()).isAfter(sameInstant.toInstant());
        List<UUID> rest = walked.subList(1, 24).stream().map(TaskCompletionResponse::id).toList();
        assertThat(rest).containsExactlyElementsOf(inserted.stream()
                .sorted(Comparator.comparing(UUID::toString).reversed()).toList());
    }

    @Test
    void listingAPageOfTasksWithRotationDoesNotIssueOneQueryPerTask() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID few = household(ana, luis, eva);
        for (int i = 0; i < 2; i++) {
            createTask(ana, few, recurring(TaskRecurrence.WEEKLY, today(), null, List.of(luis.id(), eva.id())));
        }
        TestUser marta = users().create("Marta");
        TestUser pablo = users().create("Pablo");
        TestUser sara = users().create("Sara");
        UUID many = household(marta, pablo, sara);
        for (int i = 0; i < 100; i++) {
            createTask(marta, many, recurring(TaskRecurrence.DAILY, today().plusDays(i % 7), null,
                    List.of(pablo.id(), sara.id(), marta.id())));
        }

        // Páginas llenas en ambos casos: Spring Data solo lanza el count si la página está completa.
        long fewStatements = statementsFor(() -> assertThat(listTasks(eva, few, "?size=2").content()).hasSize(2));
        long manyStatements = statementsFor(() -> assertThat(listTasks(sara, many, "?size=100").content())
                .hasSize(100).allSatisfy(t -> assertThat(t.rotation()).hasSize(3)));

        assertThat(manyStatements).as("sentencias con 100 tareas vs con 2").isEqualTo(fewStatements);
        // membresía + página + count + rotaciones en bloque (+ margen para la autenticación)
        assertThat(manyStatements).isLessThanOrEqualTo(6);
    }

    @Test
    void detailAndCompletionLoadTheRotationWithoutExtraQueriesPerMember() {
        TestUser ana = users().create("Ana");
        List<TestUser> members = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            members.add(users().create("M" + i));
        }
        UUID h = household(ana, members.toArray(TestUser[]::new));
        TaskResponse task = createTask(ana, h, recurring(TaskRecurrence.DAILY, today(), null,
                members.stream().map(TestUser::id).toList()));

        assertThat(statementsFor(() -> getTask(ana, h, task.id()))).isLessThanOrEqualTo(3);
        // membresía + bloqueo + rotación + miembros activos + insert compleción + update tarea (+ margen)
        assertThat(statementsFor(() -> complete(ana, h, task.id()))).isLessThanOrEqualTo(9);
    }

    /** Sentencias JDBC preparadas durante {@code action} (estadísticas de Hibernate activadas solo aquí). */
    private long statementsFor(Runnable action) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            action.run();
            return stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(wasEnabled);
        }
    }
}
