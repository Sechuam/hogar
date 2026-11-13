package com.hogar.income.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IncomeTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void createsWithNormalizedData() {
        Income income = Income.create(HOUSEHOLD, USER, new BigDecimal("1500.5"), "  Nómina ",
                LocalDate.of(2026, 3, 1), true, LocalDate.of(2026, 12, 31), NOW);

        assertThat(income.getId()).isNotNull();
        assertThat(income.getHouseholdId()).isEqualTo(HOUSEHOLD);
        assertThat(income.getUserId()).isEqualTo(USER);
        assertThat(income.getAmount()).isEqualTo(new BigDecimal("1500.50"));
        assertThat(income.getConcept()).isEqualTo("Nómina");
        assertThat(income.getStartDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(income.isRecurring()).isTrue();
        assertThat(income.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(income.getCreatedAt()).isEqualTo(NOW);
        assertThat(income.getUpdatedAt()).isEqualTo(NOW);
        assertThat(income.getVersion()).isNull();
    }

    @Test
    void acceptsTrailingZerosBeyondTwoDecimals() {
        Income income = oneOff(new BigDecimal("10.500"), LocalDate.of(2026, 3, 1));

        assertThat(income.getAmount()).isEqualTo(new BigDecimal("10.50"));
    }

    @Test
    void rejectsInvalidAmounts() {
        LocalDate date = LocalDate.of(2026, 3, 1);
        assertThatThrownBy(() -> oneOff(BigDecimal.ZERO, date)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> oneOff(new BigDecimal("-1"), date)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> oneOff(new BigDecimal("1.001"), date)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> oneOff(new BigDecimal("1E+17"), date)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> oneOff(null, date)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsInvalidConcept() {
        LocalDate date = LocalDate.of(2026, 3, 1);
        BigDecimal amount = BigDecimal.TEN;
        assertThatThrownBy(() -> Income.create(HOUSEHOLD, USER, amount, " ", date, false, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Income.create(HOUSEHOLD, USER, amount, null, date, false, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Income.create(HOUSEHOLD, USER, amount, "x".repeat(201), date, false, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Income.create(HOUSEHOLD, USER, amount, "x".repeat(200), date, false, null, NOW).getConcept())
                .hasSize(200);
    }

    @Test
    void endDateOnlyForRecurringAndNotBeforeStart() {
        LocalDate date = LocalDate.of(2026, 3, 15);
        BigDecimal amount = BigDecimal.TEN;
        assertThatThrownBy(() -> Income.create(HOUSEHOLD, USER, amount, "Bonus", date, false, date, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Income.create(HOUSEHOLD, USER, amount, "Nómina", date, true, date.minusDays(1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Income.create(HOUSEHOLD, USER, amount, "Nómina", date, true, date, NOW).getEndDate())
                .isEqualTo(date);
        assertThatThrownBy(() -> Income.create(HOUSEHOLD, USER, amount, "Nómina", null, true, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void requiresIdsAndClock() {
        LocalDate date = LocalDate.of(2026, 3, 1);
        assertThatThrownBy(() -> Income.create(null, USER, BigDecimal.TEN, "x", date, false, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Income.create(HOUSEHOLD, null, BigDecimal.TEN, "x", date, false, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Income.create(HOUSEHOLD, USER, BigDecimal.TEN, "x", date, false, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void updateReplacesEditableDataAndTouchesUpdatedAt() {
        Income income = Income.create(HOUSEHOLD, USER, new BigDecimal("1500"), "Nómina",
                LocalDate.of(2026, 3, 1), true, null, NOW);

        income.update(new BigDecimal("200"), "Bonus", LocalDate.of(2026, 4, 2), false, null, NOW.plusSeconds(60));

        assertThat(income.getAmount()).isEqualTo(new BigDecimal("200.00"));
        assertThat(income.getConcept()).isEqualTo("Bonus");
        assertThat(income.getStartDate()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(income.isRecurring()).isFalse();
        assertThat(income.getEndDate()).isNull();
        assertThat(income.getCreatedAt()).isEqualTo(NOW);
        assertThat(income.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void failedUpdateLeavesIncomeUntouched() {
        Income income = oneOff(BigDecimal.TEN, LocalDate.of(2026, 3, 1));

        assertThatThrownBy(() -> income.update(BigDecimal.ONE, "Otro", LocalDate.of(2026, 3, 1), false,
                LocalDate.of(2026, 4, 1), NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);

        assertThat(income.getAmount()).isEqualTo(new BigDecimal("10.00"));
        assertThat(income.getUpdatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest(name = "puntual {0} aplica a {1}: {2}")
    @CsvSource({
            "2026-03-01, 2026-03, true",
            "2026-03-31, 2026-03, true",
            "2026-03-31, 2026-04, false",
            "2026-03-01, 2026-02, false",
            "2025-03-15, 2026-03, false"
    })
    void oneOffAppliesOnlyToItsMonth(LocalDate date, YearMonth month, boolean expected) {
        assertThat(oneOff(BigDecimal.TEN, date).appliesTo(month)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "recurrente {0}..{1} aplica a {2}: {3}")
    @CsvSource({
            "2026-03-15,           , 2026-02, false",
            "2026-03-15,           , 2026-03, true",
            "2026-03-15,           , 2030-12, true",
            "2026-03-15, 2026-05-10, 2026-03, true",
            "2026-03-15, 2026-05-10, 2026-04, true",
            "2026-03-15, 2026-05-10, 2026-05, true",
            "2026-03-15, 2026-05-10, 2026-06, false",
            "2026-03-31, 2026-03-31, 2026-03, true",
            "2026-03-31, 2026-03-31, 2026-04, false"
    })
    void recurringAppliesFromStartMonthToEndMonth(LocalDate start, LocalDate end, YearMonth month, boolean expected) {
        Income income = Income.create(HOUSEHOLD, USER, BigDecimal.TEN, "Nómina", start, true, end, NOW);

        assertThat(income.appliesTo(month)).isEqualTo(expected);
    }

    @Test
    void equalityById() {
        Income a = oneOff(BigDecimal.TEN, LocalDate.of(2026, 3, 1));
        Income b = oneOff(BigDecimal.TEN, LocalDate.of(2026, 3, 1));

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(a);
        assertThat(a.toString()).contains(a.getId().toString());
    }

    private static Income oneOff(BigDecimal amount, LocalDate date) {
        return Income.create(HOUSEHOLD, USER, amount, "Bonus", date, false, null, NOW);
    }
}
