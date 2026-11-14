package com.hogar.task.domain;

/** Una tarea recurrente siempre está PENDING (al completarla pasa a la siguiente ocurrencia). */
public enum TaskStatus {
    PENDING,
    DONE
}
