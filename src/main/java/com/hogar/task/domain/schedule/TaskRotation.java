package com.hogar.task.domain.schedule;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turno rotatorio de una tarea recurrente (contrato Fase 3, §2).
 *
 * <p>Clase pura y determinista: sin Spring ni BD. Quién está activo lo decide el llamante.
 */
public final class TaskRotation {

    private TaskRotation() {
    }

    /**
     * Siguiente asignado de la rotación: recorre la lista circularmente empezando <em>después</em>
     * de {@code current} (o por el principio si {@code current} es {@code null} o no está en la
     * lista) y devuelve el primer miembro activo. Puede devolver {@code current} si es el único
     * activo; si no hay ninguno activo devuelve vacío (la tarea queda sin asignar).
     *
     * @param rotation      orden de la rotación, sin {@code null} ni repetidos (puede ser vacía)
     * @param current       asignado actual; {@code null} si la tarea no tiene asignado
     * @param activeMembers miembros activos del hogar
     * @return el siguiente asignado activo, o vacío si no hay ninguno
     * @throws NullPointerException     si {@code rotation}, {@code activeMembers} o algún elemento
     *                                  de {@code rotation} es {@code null}
     * @throws IllegalArgumentException si {@code rotation} contiene repetidos
     */
    public static Optional<UUID> nextAssignee(List<UUID> rotation, UUID current, Set<UUID> activeMembers) {
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(activeMembers, "activeMembers");
        requireDistinct(rotation);

        int size = rotation.size();
        int start = current == null ? 0 : rotation.indexOf(current) + 1; // indexOf = -1 → 0
        for (int step = 0; step < size; step++) {
            UUID candidate = rotation.get((start + step) % size);
            if (activeMembers.contains(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static void requireDistinct(List<UUID> rotation) {
        Set<UUID> seen = new HashSet<>(rotation.size() * 2);
        for (UUID member : rotation) {
            Objects.requireNonNull(member, "rotation contiene null");
            if (!seen.add(member)) {
                throw new IllegalArgumentException("rotation contiene un miembro repetido: " + member);
            }
        }
    }
}
