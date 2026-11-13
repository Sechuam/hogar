package com.hogar.income.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.household.domain.Household;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.income.domain.Income;
import com.hogar.income.repository.IncomeRepository.UserIncomeTotal;
import com.hogar.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
class IncomeRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Autowired
    private IncomeRepository incomes;

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
    void persistsAndReloadsAllFields() {
        Income saved = incomes.saveAndFlush(Income.create(householdId, ana, new BigDecimal("1500.5"), "Nómina",
                LocalDate.of(2026, 3, 1), true, LocalDate.of(2026, 12, 31), NOW));
        em.clear();

        Income reloaded = incomes.findByIdAndHouseholdId(saved.getId(), householdId).orElseThrow();

        assertThat(reloaded.getUserId()).isEqualTo(ana);
        assertThat(reloaded.getAmount()).isEqualTo(new BigDecimal("1500.50"));
        assertThat(reloaded.getConcept()).isEqualTo("Nómina");
        assertThat(reloaded.getStartDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(reloaded.isRecurring()).isTrue();
        assertThat(reloaded.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(reloaded.getVersion()).isZero();
        assertThat(incomes.findByIdAndHouseholdId(saved.getId(), otherHouseholdId)).isEmpty();
    }

    @Test
    void updateIncrementsVersion() {
        Income income = incomes.saveAndFlush(oneOff(householdId, ana, "100", LocalDate.of(2026, 3, 5)));

        income.update(new BigDecimal("120"), "Bonus", LocalDate.of(2026, 3, 6), false, null, NOW.plusSeconds(5));
        incomes.saveAndFlush(income);
        em.clear();

        Income reloaded = incomes.findById(income.getId()).orElseThrow();
        assertThat(reloaded.getAmount()).isEqualTo(new BigDecimal("120.00"));
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(reloaded.getVersion()).isEqualTo(1);
    }

    @Test
    void monthFilterAndSumCoverEveryBoundary() {
        monthFixture();

        Page<Income> march = incomes.search(householdId, null, MARCH, PageRequest.of(0, 100));

        assertThat(march.getContent()).extracting(Income::getConcept)
                .containsExactlyInAnyOrder("dia-1", "ultimo-dia", "recurrente-desde-mitad", "acaba-mitad",
                        "acaba-dia-1");
        assertThat(totals(incomes.sumByUserForMonth(householdId, MARCH))).containsExactlyInAnyOrderEntriesOf(Map.of(
                ana, new BigDecimal("110.00"),
                bob, new BigDecimal("1050.00"),
                carla, new BigDecimal("5.00")));
    }

    @Test
    void sqlFilterMatchesDomainAppliesToForEveryMonth() {
        List<Income> all = monthFixture();

        for (YearMonth month = YearMonth.of(2024, 12); !month.isAfter(YearMonth.of(2026, 6));
                month = month.plusMonths(1)) {
            YearMonth m = month;
            List<UUID> expected = all.stream()
                    .filter(i -> i.getHouseholdId().equals(householdId) && i.appliesTo(m))
                    .map(Income::getId).toList();

            assertThat(incomes.search(householdId, null, m, Pageable.unpaged()).getContent())
                    .as("ingresos de %s", m).extracting(Income::getId).containsExactlyInAnyOrderElementsOf(expected);

            Map<UUID, BigDecimal> expectedTotals = all.stream()
                    .filter(i -> i.getHouseholdId().equals(householdId) && i.appliesTo(m))
                    .collect(Collectors.toMap(Income::getUserId, Income::getAmount, BigDecimal::add));
            assertThat(totals(incomes.sumByUserForMonth(householdId, m)))
                    .as("sumas de %s", m).containsExactlyInAnyOrderEntriesOf(expectedTotals);
        }
    }

    @Test
    void sumIsEmptyWithoutIncomes() {
        assertThat(incomes.sumByUserForMonth(householdId, MARCH)).isEmpty();
    }

    @Test
    void searchFiltersByUserPaginatesAndOrdersByDateDesc() {
        Income first = incomes.save(oneOff(householdId, ana, "1", LocalDate.of(2026, 1, 10)));
        Income second = incomes.save(oneOff(householdId, ana, "2", LocalDate.of(2026, 3, 10)));
        Income sameDateLater = incomes.save(Income.create(householdId, ana, BigDecimal.ONE, "tarde",
                LocalDate.of(2026, 3, 10), false, null, NOW.plusSeconds(60)));
        Income third = incomes.save(oneOff(householdId, ana, "3", LocalDate.of(2026, 2, 10)));
        incomes.save(oneOff(householdId, bob, "4", LocalDate.of(2026, 3, 10)));
        incomes.save(oneOff(otherHouseholdId, ana, "5", LocalDate.of(2026, 3, 10)));
        incomes.flush();

        // El orden pedido en el Pageable se ignora: lo fija el contrato.
        Page<Income> page0 = incomes.search(householdId, ana, null, PageRequest.of(0, 2, Sort.by("concept")));
        Page<Income> page1 = incomes.search(householdId, ana, null, PageRequest.of(1, 2));

        assertThat(page0.getTotalElements()).isEqualTo(4);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getContent()).containsExactly(sameDateLater, second);
        assertThat(page1.getContent()).containsExactly(third, first);
        assertThat(incomes.search(householdId, null, null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(5);
        assertThat(incomes.search(householdId, ana, MARCH, PageRequest.of(0, 10)).getContent())
                .containsExactly(sameDateLater, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // importe no positivo
            "insert into incomes values (?, ?, ?, 0, 'x', '2026-03-01', false, null, now(), now(), 0)",
            // fecha de fin en un puntual
            "insert into incomes values (?, ?, ?, 1, 'x', '2026-03-01', false, '2026-03-02', now(), now(), 0)",
            // fecha de fin anterior al inicio
            "insert into incomes values (?, ?, ?, 1, 'x', '2026-03-01', true, '2026-02-28', now(), now(), 0)"
    })
    void databaseChecksRejectInvalidRows(String insert) {
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), householdId, ana))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRequiresExistingHouseholdAndUser() {
        String insert = "insert into incomes values (?, ?, ?, 1, 'x', '2026-03-01', false, null, now(), now(), 0)";

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), ana))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Ingresos alrededor de marzo de 2026: todos los bordes de "aplica a un mes". */
    private List<Income> monthFixture() {
        List<Income> all = List.of(
                named(oneOff(householdId, ana, "100", LocalDate.of(2026, 3, 1)), "dia-1"),
                named(oneOff(householdId, ana, "10", LocalDate.of(2026, 3, 31)), "ultimo-dia"),
                named(oneOff(householdId, ana, "7", LocalDate.of(2026, 2, 28)), "mes-anterior"),
                named(oneOff(householdId, bob, "7", LocalDate.of(2026, 4, 1)), "mes-siguiente"),
                recurring(bob, "1000", LocalDate.of(2026, 3, 15), null, "recurrente-desde-mitad"),
                recurring(bob, "50", LocalDate.of(2025, 1, 10), LocalDate.of(2026, 3, 10), "acaba-mitad"),
                recurring(carla, "7", LocalDate.of(2025, 1, 10), LocalDate.of(2026, 2, 28), "acaba-antes"),
                recurring(carla, "7", LocalDate.of(2026, 4, 1), null, "empieza-despues"),
                recurring(carla, "5", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1), "acaba-dia-1"),
                named(oneOff(otherHouseholdId, ana, "7", LocalDate.of(2026, 3, 5)), "otro-hogar"));
        incomes.saveAllAndFlush(all);
        return all;
    }

    private Income recurring(UUID userId, String amount, LocalDate start, LocalDate end, String concept) {
        return Income.create(householdId, userId, new BigDecimal(amount), concept, start, true, end, NOW);
    }

    private static Income named(Income income, String concept) {
        income.update(income.getAmount(), concept, income.getStartDate(), income.isRecurring(), income.getEndDate(),
                NOW);
        return income;
    }

    private static Income oneOff(UUID household, UUID userId, String amount, LocalDate date) {
        return Income.create(household, userId, new BigDecimal(amount), "ingreso " + amount, date, false, null, NOW);
    }

    private static Map<UUID, BigDecimal> totals(List<UserIncomeTotal> rows) {
        return rows.stream().collect(Collectors.toMap(UserIncomeTotal::getUserId, UserIncomeTotal::getTotal));
    }
}
