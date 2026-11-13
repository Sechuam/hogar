package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.shared.web.PageResponse;
import com.hogar.support.TestUsers.TestUser;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * QA Fase 2: el listado de gastos pagina de forma estable (sin duplicar ni perder filas aunque
 * todos tengan la misma fecha) y no hace N+1 al cargar las partes (D-040).
 */
class ExpenseListingIT extends FinanceApiSupport {

    private static final LocalDate SAME_DAY = LocalDate.of(2026, 7, 7);

    @Autowired
    private EntityManagerFactory emf;

    private record Trio(UUID id, TestUser ana, TestUser luis, TestUser eva) {
    }

    private Trio trio() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis, eva);
        return new Trio(id, ana, luis, eva);
    }

    @Test
    void walkingEveryPageOfSameDayExpensesReturnsEachExactlyOnceInContractOrder() {
        Trio t = trio();
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 37; i++) {
            created.add(createExpense(i % 2 == 0 ? t.ana() : t.luis(), t.id(),
                    expense("1." + String.format("%02d", i), SAME_DAY, null)).id());
        }

        List<ExpenseResponse> walked = new ArrayList<>();
        PageResponse<ExpenseResponse> page;
        int n = 0;
        do {
            page = listExpenses(t.eva(), t.id(), "?size=5&page=" + n++);
            assertThat(page.totalElements()).isEqualTo(37);
            walked.addAll(page.content());
        } while (n < page.totalPages());

        assertThat(page.totalPages()).isEqualTo(8);
        assertThat(walked).extracting(ExpenseResponse::id).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(created);
        // Orden del contrato: misma fecha → alta desc (y id desc como desempate total).
        for (int i = 1; i < walked.size(); i++) {
            ExpenseResponse prev = walked.get(i - 1);
            ExpenseResponse cur = walked.get(i);
            assertThat(prev.createdAt()).isAfterOrEqualTo(cur.createdAt());
        }
        // Dos recorridos dan el mismo orden.
        List<UUID> again = new ArrayList<>();
        for (int p = 0; p < 8; p++) {
            again.addAll(listExpenses(t.ana(), t.id(), "?size=5&page=" + p).content().stream()
                    .map(ExpenseResponse::id).toList());
        }
        assertThat(again).containsExactlyElementsOf(walked.stream().map(ExpenseResponse::id).toList());
        assertThat(listExpenses(t.eva(), t.id(), "?size=5&page=8").content()).isEmpty();
    }

    @Test
    void listingDoesNotIssueOneQueryPerExpense() {
        Trio few = trio();
        for (int i = 0; i < 2; i++) {
            createExpense(few.ana(), few.id(), expense("3.00", SAME_DAY, null));
        }
        Trio many = trio();
        for (int i = 0; i < 40; i++) {
            createExpense(many.ana(), many.id(), expense("3.00", SAME_DAY.minusDays(i % 5), null));
        }

        long fewStatements = statementsFor(() -> assertThat(
                listExpenses(few.eva(), few.id(), "?size=100").content()).hasSize(2));
        long manyStatements = statementsFor(() -> assertThat(
                listExpenses(many.eva(), many.id(), "?size=100").content()).hasSize(40)
                .allSatisfy(e -> assertThat(e.shares()).hasSize(3)));

        assertThat(manyStatements).as("sentencias con 40 gastos vs con 2").isEqualTo(fewStatements);
        // membresía + página + count + partes en bloque (+ margen para la autenticación)
        assertThat(manyStatements).isLessThanOrEqualTo(6);
    }

    @Test
    void detailLoadsTheExpenseAndItsSharesTogether() {
        Trio t = trio();
        UUID id = createExpense(t.ana(), t.id(), expense("3.00", SAME_DAY, null)).id();
        long statements = statementsFor(() -> getExpense(t.luis(), t.id(), id));
        assertThat(statements).isLessThanOrEqualTo(3);
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
