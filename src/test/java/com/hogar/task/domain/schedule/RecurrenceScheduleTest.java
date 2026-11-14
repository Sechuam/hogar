package com.hogar.task.domain.schedule;

import static com.hogar.task.domain.TaskRecurrence.DAILY;
import static com.hogar.task.domain.TaskRecurrence.MONTHLY;
import static com.hogar.task.domain.TaskRecurrence.NONE;
import static com.hogar.task.domain.TaskRecurrence.WEEKLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.task.domain.TaskRecurrence;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("RecurrenceSchedule.nextDueDate")
class RecurrenceScheduleTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static LocalDate next(TaskRecurrence r, String anchor, String currentDue, String today) {
        return RecurrenceSchedule.nextDueDate(r, d(anchor), d(currentDue), d(today));
    }

    @Nested
    @DisplayName("diaria")
    class Daily {

        @Test
        @DisplayName("a tiempo: el día siguiente")
        void onTime() {
            assertThat(next(DAILY, "2026-03-01", "2026-03-10", "2026-03-10")).isEqualTo(d("2026-03-11"));
        }

        @Test
        @DisplayName("antes de tiempo: un periodo desde currentDue")
        void early() {
            assertThat(next(DAILY, "2026-03-01", "2026-03-10", "2026-03-07")).isEqualTo(d("2026-03-11"));
        }

        @Test
        @DisplayName("tarde: el día siguiente a hoy, sin atrasos acumulados")
        void late() {
            assertThat(next(DAILY, "2026-03-01", "2026-03-10", "2026-03-25")).isEqualTo(d("2026-03-26"));
        }

        @Test
        @DisplayName("cruza fin de año y 29-feb")
        void crossesYearAndLeapDay() {
            assertThat(next(DAILY, "2027-12-01", "2027-12-31", "2027-12-31")).isEqualTo(d("2028-01-01"));
            assertThat(next(DAILY, "2028-02-01", "2028-02-28", "2028-02-28")).isEqualTo(d("2028-02-29"));
        }
    }

    @Nested
    @DisplayName("semanal")
    class Weekly {

        @Test
        @DisplayName("a tiempo: +7 días")
        void onTime() {
            assertThat(next(WEEKLY, "2026-01-05", "2026-01-12", "2026-01-12")).isEqualTo(d("2026-01-19"));
        }

        @Test
        @DisplayName("antes de tiempo: +7 días desde currentDue")
        void early() {
            assertThat(next(WEEKLY, "2026-01-05", "2026-01-12", "2026-01-08")).isEqualTo(d("2026-01-19"));
        }

        @Test
        @DisplayName("tarde un día: la siguiente ocurrencia del ancla")
        void lateOnePeriod() {
            assertThat(next(WEEKLY, "2026-01-05", "2026-01-12", "2026-01-13")).isEqualTo(d("2026-01-19"));
        }

        @Test
        @DisplayName("tarde muchos periodos: conserva el día de la semana del ancla")
        void lateManyPeriods() {
            LocalDate result = next(WEEKLY, "2026-01-05", "2026-01-12", "2026-06-10");
            assertThat(result).isEqualTo(d("2026-06-15"));
            assertThat(result.getDayOfWeek()).isEqualTo(d("2026-01-05").getDayOfWeek());
        }

        @Test
        @DisplayName("hoy cae justo en una ocurrencia: salta a la siguiente")
        void todayOnOccurrence() {
            assertThat(next(WEEKLY, "2026-01-05", "2026-01-12", "2026-02-02")).isEqualTo(d("2026-02-09"));
        }
    }

    @Nested
    @DisplayName("mensual")
    class Monthly {

        @Test
        @DisplayName("a tiempo y antes de tiempo: mismo día del mes siguiente")
        void onTimeAndEarly() {
            assertThat(next(MONTHLY, "2026-01-15", "2026-02-15", "2026-02-15")).isEqualTo(d("2026-03-15"));
            assertThat(next(MONTHLY, "2026-01-15", "2026-02-15", "2026-02-01")).isEqualTo(d("2026-03-15"));
        }

        @Test
        @DisplayName("tarde un periodo y muchos periodos")
        void late() {
            assertThat(next(MONTHLY, "2026-01-15", "2026-02-15", "2026-03-15")).isEqualTo(d("2026-04-15"));
            assertThat(next(MONTHLY, "2026-01-15", "2026-02-15", "2029-07-20")).isEqualTo(d("2029-08-15"));
        }

        @ParameterizedTest(name = "día 31: {0} → {1}")
        @CsvSource({
                "2026-01-31, 2026-02-28",
                "2026-02-28, 2026-03-31",
                "2026-03-31, 2026-04-30",
                "2026-04-30, 2026-05-31",
                "2026-08-31, 2026-09-30",
                "2026-12-31, 2027-01-31",
                "2028-01-31, 2028-02-29",
                "2028-02-29, 2028-03-31",
        })
        @DisplayName("ancla día 31: último día del mes siguiente y vuelta al 31 sin deriva")
        void anchorOn31NoDrift(String currentDue, String expected) {
            LocalDate anchor = d("2025-12-31");
            assertThat(RecurrenceSchedule.nextDueDate(MONTHLY, anchor, d(currentDue), d(currentDue)))
                    .isEqualTo(d(expected));
        }

        @Test
        @DisplayName("ancla día 31 encadenando un año entero de compleciones a tiempo")
        void anchorOn31FullYearChain() {
            LocalDate anchor = d("2026-01-31");
            LocalDate due = anchor;
            StringBuilder seen = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                due = RecurrenceSchedule.nextDueDate(MONTHLY, anchor, due, due);
                seen.append(due.getDayOfMonth()).append(' ');
            }
            assertThat(seen.toString().trim()).isEqualTo("28 31 30 31 30 31 31 30 31 30 31 31");
            assertThat(due).isEqualTo(d("2027-01-31"));
        }

        @Test
        @DisplayName("ancla día 31 completada tarde el 28-feb: la siguiente es el 31-mar")
        void anchorOn31LateInFebruary() {
            assertThat(next(MONTHLY, "2026-01-31", "2026-01-31", "2026-02-28")).isEqualTo(d("2026-03-31"));
            assertThat(next(MONTHLY, "2026-01-31", "2026-01-31", "2026-02-27")).isEqualTo(d("2026-02-28"));
        }

        @Test
        @DisplayName("ancla 29-feb bisiesto: 28-feb en años no bisiestos y 29-feb al volver al bisiesto")
        void anchorOnLeapDay() {
            LocalDate anchor = d("2028-02-29");
            assertThat(RecurrenceSchedule.nextDueDate(MONTHLY, anchor, anchor, anchor)).isEqualTo(d("2028-03-29"));
            assertThat(next(MONTHLY, "2028-02-29", "2029-01-29", "2029-01-29")).isEqualTo(d("2029-02-28"));
            assertThat(next(MONTHLY, "2028-02-29", "2029-02-28", "2029-02-28")).isEqualTo(d("2029-03-29"));
            assertThat(next(MONTHLY, "2028-02-29", "2032-01-29", "2032-01-29")).isEqualTo(d("2032-02-29"));
        }

        @Test
        @DisplayName("ancla 29-feb, tarde varios años: aterriza en un 29 sin deriva")
        void anchorOnLeapDayLateYears() {
            assertThat(next(MONTHLY, "2028-02-29", "2028-03-29", "2031-05-30")).isEqualTo(d("2031-06-29"));
        }
    }

    @Nested
    @DisplayName("ancla posterior a la referencia (tras un PUT)")
    class AnchorAfterReference {

        @ParameterizedTest
        @EnumSource(value = TaskRecurrence.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("ancla futura respecto a currentDue y hoy: el propio ancla")
        void anchorInTheFuture(TaskRecurrence r) {
            assertThat(next(r, "2026-05-31", "2026-05-10", "2026-05-01")).isEqualTo(d("2026-05-31"));
        }

        @ParameterizedTest
        @EnumSource(value = TaskRecurrence.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("ancla igual a currentDue (caso real tras un PUT): un periodo después")
        void anchorEqualsCurrentDue(TaskRecurrence r) {
            LocalDate anchor = d("2026-05-31");
            LocalDate expected = switch (r) {
                case DAILY -> d("2026-06-01");
                case WEEKLY -> d("2026-06-07");
                case MONTHLY -> d("2026-06-30");
                case NONE -> throw new AssertionError();
            };
            assertThat(RecurrenceSchedule.nextDueDate(r, anchor, anchor, d("2026-05-20"))).isEqualTo(expected);
        }

        @Test
        @DisplayName("ancla posterior a currentDue pero anterior a hoy: siguiente ocurrencia tras hoy")
        void anchorBetweenCurrentDueAndToday() {
            assertThat(next(WEEKLY, "2026-05-04", "2026-05-01", "2026-05-20")).isEqualTo(d("2026-05-25"));
        }
    }

    @Nested
    @DisplayName("fechas extremas")
    class Extremes {

        @Test
        @Timeout(1)
        @DisplayName("ancla en el año 1 y hoy en el 9999: O(1), sin iterar día a día")
        void farApartIsFast() {
            assertThat(next(DAILY, "0001-01-01", "0001-01-01", "9999-06-15")).isEqualTo(d("9999-06-16"));
            assertThat(next(WEEKLY, "0001-01-01", "0001-01-01", "9999-06-15"))
                    .satisfies(r -> assertThat(ChronoUnit.DAYS.between(d("0001-01-01"), r) % 7).isZero())
                    .isAfter(d("9999-06-15"))
                    .isBeforeOrEqualTo(d("9999-06-22"));
            assertThat(next(MONTHLY, "0001-01-31", "0001-01-31", "9999-06-15")).isEqualTo(d("9999-06-30"));
        }

        @Test
        @Timeout(1)
        @DisplayName("de LocalDate.MIN a casi LocalDate.MAX: sigue siendo O(1)")
        void wholeRange() {
            LocalDate beforeMax = LocalDate.MAX.minusDays(1);
            assertThat(RecurrenceSchedule.nextDueDate(DAILY, LocalDate.MIN, LocalDate.MIN, beforeMax))
                    .isEqualTo(LocalDate.MAX);
            assertThat(RecurrenceSchedule.nextDueDate(MONTHLY, LocalDate.MIN.withDayOfMonth(31),
                    LocalDate.MIN.withDayOfMonth(31), LocalDate.MAX.withDayOfMonth(15)))
                    .isEqualTo(LocalDate.MAX);
        }

        @Test
        @DisplayName("si la siguiente ocurrencia excede LocalDate.MAX: DateTimeException")
        void beyondMax() {
            LocalDate max = LocalDate.MAX;
            assertThatThrownBy(() -> RecurrenceSchedule.nextDueDate(DAILY, max.minusDays(30), max, max))
                    .isInstanceOf(DateTimeException.class);
            assertThatThrownBy(() -> RecurrenceSchedule.nextDueDate(MONTHLY, max.minusMonths(1), max.minusMonths(1), max))
                    .isInstanceOf(DateTimeException.class);
        }
    }

    @Nested
    @DisplayName("validación de argumentos")
    class Validation {

        @Test
        @DisplayName("NONE: IllegalArgumentException")
        void noneRejected() {
            assertThatThrownBy(() -> next(NONE, "2026-01-01", "2026-01-01", "2026-01-01"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NONE");
        }

        @Test
        @DisplayName("cualquier argumento nulo: NullPointerException con su nombre")
        void nullsRejected() {
            LocalDate x = d("2026-01-01");
            assertThatNullPointerException()
                    .isThrownBy(() -> RecurrenceSchedule.nextDueDate(null, x, x, x)).withMessage("recurrence");
            assertThatNullPointerException()
                    .isThrownBy(() -> RecurrenceSchedule.nextDueDate(DAILY, null, x, x)).withMessage("anchor");
            assertThatNullPointerException()
                    .isThrownBy(() -> RecurrenceSchedule.nextDueDate(DAILY, x, null, x)).withMessage("currentDue");
            assertThatNullPointerException()
                    .isThrownBy(() -> RecurrenceSchedule.nextDueDate(DAILY, x, x, null)).withMessage("today");
        }
    }
}
