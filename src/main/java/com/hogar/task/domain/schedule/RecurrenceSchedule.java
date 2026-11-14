package com.hogar.task.domain.schedule;

import com.hogar.task.domain.TaskRecurrence;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Cálculo de la siguiente fecha de una tarea recurrente (contrato Fase 3, §2).
 *
 * <p>Las ocurrencias se derivan siempre del ancla: la {@code k}-ésima es {@code anchor + k·periodo}
 * con {@code k ≥ 0} ({@code plusDays}, {@code plusWeeks}, {@code plusMonths}). Sumar meses desde el
 * ancla, y no desde la fecha anterior, evita la deriva: 31-ene → 28/29-feb → 31-mar.
 *
 * <p>Clase pura y determinista: sin Spring, sin BD, sin reloj (el "hoy" lo pasa quien llama).
 * Coste O(1): {@code k} se estima con {@link ChronoUnit#between} y se corrige con un par de pasos.
 */
public final class RecurrenceSchedule {

    private RecurrenceSchedule() {
    }

    /**
     * Menor ocurrencia del ancla estrictamente posterior a {@code max(currentDue, today)}.
     *
     * <p>Completar antes de tiempo avanza un periodo desde {@code currentDue}; completar tarde salta
     * a la primera ocurrencia futura (no se acumulan atrasos). Si el ancla ya es posterior a la
     * referencia, el resultado es el propio ancla (no hay ocurrencias anteriores al ancla).
     *
     * @param recurrence periodicidad, distinta de {@link TaskRecurrence#NONE}
     * @param anchor     fecha ancla de la serie (ocurrencia {@code k = 0})
     * @param currentDue fecha de vencimiento actual de la tarea
     * @param today      fecha de hoy según el reloj del llamante
     * @return la siguiente fecha de vencimiento, siempre {@code > max(currentDue, today)}
     * @throws NullPointerException     si algún argumento es {@code null}
     * @throws IllegalArgumentException si {@code recurrence} es {@code NONE}
     * @throws java.time.DateTimeException si la siguiente ocurrencia excede {@link LocalDate#MAX}
     */
    public static LocalDate nextDueDate(TaskRecurrence recurrence, LocalDate anchor,
                                        LocalDate currentDue, LocalDate today) {
        Objects.requireNonNull(recurrence, "recurrence");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(currentDue, "currentDue");
        Objects.requireNonNull(today, "today");
        ChronoUnit unit = unitOf(recurrence);

        LocalDate reference = currentDue.isAfter(today) ? currentDue : today;
        if (anchor.isAfter(reference)) {
            return anchor;
        }

        // Estimación: nº de periodos completos entre ancla y referencia. Con meses puede quedarse
        // corta (31-ene → 28-feb cuenta 0 meses) o, por seguridad, pasarse: se ajusta en ambos sentidos.
        long k = unit.between(anchor, reference);
        while (k > 0 && occurrence(anchor, unit, k).isAfter(reference)) {
            k--;
        }
        while (!occurrence(anchor, unit, k).isAfter(reference)) {
            k++;
        }
        return occurrence(anchor, unit, k);
    }

    private static ChronoUnit unitOf(TaskRecurrence recurrence) {
        return switch (recurrence) {
            case DAILY -> ChronoUnit.DAYS;
            case WEEKLY -> ChronoUnit.WEEKS;
            case MONTHLY -> ChronoUnit.MONTHS;
            case NONE -> throw new IllegalArgumentException("una tarea NONE no tiene siguiente fecha");
        };
    }

    /** Ocurrencia {@code k} del ancla; {@code plusMonths} recorta al último día del mes si hace falta. */
    static LocalDate occurrence(LocalDate anchor, ChronoUnit unit, long k) {
        return anchor.plus(k, unit);
    }
}
