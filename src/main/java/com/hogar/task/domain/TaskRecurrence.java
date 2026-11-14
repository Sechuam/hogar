package com.hogar.task.domain;

/**
 * Periodicidad de una tarea. Las ocurrencias se calculan desde la fecha ancla
 * ({@code anchor + k·periodo}), así una tarea mensual del día 31 no deriva al 28 para siempre.
 */
public enum TaskRecurrence {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY
}
