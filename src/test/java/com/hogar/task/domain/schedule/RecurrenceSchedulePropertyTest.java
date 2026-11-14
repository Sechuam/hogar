package com.hogar.task.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.task.domain.TaskRecurrence;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Propiedades de {@link RecurrenceSchedule#nextDueDate} sobre muchos casos aleatorios reproducibles
 * (semilla fija): anclas en 1990–2040 con sesgo a días 28–31, currentDue = una ocurrencia del ancla
 * (como en producción) o una fecha arbitraria, y "hoy" antes, igual o muy después de currentDue.
 *
 * <p>Para cada caso, con {@code ref = max(currentDue, today)} y el resultado {@code r}:
 * {@code r > ref}; {@code r} es la ocurrencia {@code k ≥ 0} del ancla; y es la mínima, es decir,
 * la ocurrencia {@code k − 1} (si existe) no es posterior a {@code ref}.
 */
@DisplayName("RecurrenceSchedule: propiedades con casos aleatorios")
class RecurrenceSchedulePropertyTest {

    private static final int ITERATIONS = 20_000;
    private static final long SEED = 20_261_114L;
    private static final LocalDate MIN_ANCHOR = LocalDate.of(1990, 1, 1);
    private static final int ANCHOR_SPAN_DAYS = 365 * 50;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = TaskRecurrence.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("resultado > max(currentDue, today), es ocurrencia del ancla y es la mínima")
    void invariantsHold(TaskRecurrence recurrence) {
        ChronoUnit unit = unitOf(recurrence);
        Random random = new Random(SEED + recurrence.ordinal());
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            LocalDate anchor = randomAnchor(random);
            LocalDate currentDue = random.nextInt(4) == 0
                    ? anchor.plusDays(random.nextInt(-60, 2_000)) // arbitraria, incluso anterior al ancla
                    : anchor.plus(random.nextInt(0, 120), unit);  // una ocurrencia, como en producción
            LocalDate today = randomToday(random, currentDue);

            LocalDate result = RecurrenceSchedule.nextDueDate(recurrence, anchor, currentDue, today);
            LocalDate reference = currentDue.isAfter(today) ? currentDue : today;
            String context = "caso %d: %s anchor=%s currentDue=%s today=%s → %s"
                    .formatted(iteration, recurrence, anchor, currentDue, today, result);

            assertThat(result).as(context).isAfter(reference);
            long k = occurrenceIndex(anchor, unit, result);
            assertThat(k).as(context + " (no es ocurrencia del ancla)").isGreaterThanOrEqualTo(0);
            if (k > 0) {
                assertThat(anchor.plus(k - 1, unit)).as(context + " (no es la mínima)").isBeforeOrEqualTo(reference);
            }
            assertThat(RecurrenceSchedule.nextDueDate(recurrence, anchor, currentDue, today))
                    .as(context + " (no determinista)").isEqualTo(result);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = TaskRecurrence.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("encadenar compleciones a tiempo recorre las ocurrencias 1, 2, 3… sin deriva")
    void chainingOnTimeVisitsEveryOccurrence(TaskRecurrence recurrence) {
        ChronoUnit unit = unitOf(recurrence);
        Random random = new Random(SEED * 31 + recurrence.ordinal());
        for (int series = 0; series < 500; series++) {
            LocalDate anchor = randomAnchor(random);
            LocalDate due = anchor;
            for (int k = 1; k <= 60; k++) {
                due = RecurrenceSchedule.nextDueDate(recurrence, anchor, due, due);
                assertThat(due).as("serie %d desde %s, k=%d", series, anchor, k).isEqualTo(anchor.plus(k, unit));
            }
        }
    }

    private static ChronoUnit unitOf(TaskRecurrence recurrence) {
        return switch (recurrence) {
            case DAILY -> ChronoUnit.DAYS;
            case WEEKLY -> ChronoUnit.WEEKS;
            case MONTHLY -> ChronoUnit.MONTHS;
            case NONE -> throw new AssertionError("NONE no es recurrente");
        };
    }

    private static LocalDate randomAnchor(Random random) {
        LocalDate base = MIN_ANCHOR.plusDays(random.nextInt(ANCHOR_SPAN_DAYS));
        if (random.nextInt(3) == 0) {
            // fin de mes (28–31, incluido 29-feb cuando existe): los casos delicados de MONTHLY
            int day = Math.min(28 + random.nextInt(4), base.lengthOfMonth());
            return base.withDayOfMonth(day);
        }
        return base;
    }

    private static LocalDate randomToday(Random random, LocalDate currentDue) {
        return switch (random.nextInt(4)) {
            case 0 -> currentDue;                                       // a tiempo
            case 1 -> currentDue.minusDays(random.nextInt(1, 40));      // antes de tiempo
            case 2 -> currentDue.plusDays(random.nextInt(1, 40));       // algo tarde
            default -> currentDue.plusDays(random.nextInt(40, 5_000));  // muy tarde
        };
    }

    /**
     * Índice {@code k} tal que {@code anchor + k·unit == date}, o {@code -1} si no existe. Con meses,
     * {@code between} puede quedarse uno corto (31-ene → 28-feb), así que se prueban {@code k} y {@code k+1}.
     */
    private static long occurrenceIndex(LocalDate anchor, ChronoUnit unit, LocalDate date) {
        long estimate = unit.between(anchor, date);
        for (long k = Math.max(0, estimate - 1); k <= estimate + 1; k++) {
            if (anchor.plus(k, unit).equals(date)) {
                return k;
            }
        }
        return -1;
    }
}
