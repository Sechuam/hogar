package com.hogar.household.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Límites de un plan. Un valor {@code null} significa "sin límite" (D-068).
 *
 * @param maxMembers          miembros activos por hogar
 * @param maxOwnedHouseholds  hogares FREE de los que un usuario puede ser OWNER (se comprueba al crear)
 * @param maxStorageBytes     bytes de facturas por hogar
 * @param historyMonths       meses naturales visibles en los listados de gastos y liquidaciones
 *                            (el actual incluido); los balances siempre usan todo el histórico
 */
@Schema(description = "Límites del plan; `null` = sin límite.")
public record PlanLimits(
        @Schema(description = "Miembros activos por hogar.", example = "4", nullable = true)
        Integer maxMembers,
        @Schema(description = "Hogares FREE de los que un usuario puede ser OWNER.", example = "1", nullable = true)
        Integer maxOwnedHouseholds,
        @Schema(description = "Bytes de facturas adjuntas por hogar.", example = "52428800", nullable = true)
        Long maxStorageBytes,
        @Schema(description = "Meses naturales visibles en los listados de gastos y liquidaciones (el actual incluido).",
                example = "12", nullable = true)
        Integer historyMonths) {

    public static final PlanLimits UNLIMITED = new PlanLimits(null, null, null, null);

    /** ¿Cabe un hogar con {@code members} miembros activos? */
    public boolean allowsMembers(long members) {
        return maxMembers == null || members <= maxMembers;
    }

    /** ¿Caben {@code bytes} de facturas en total? */
    public boolean allowsStorage(long bytes) {
        return maxStorageBytes == null || bytes <= maxStorageBytes;
    }

    /** ¿Puede un usuario que ya es OWNER de {@code owned} hogares FREE crear otro? */
    public boolean allowsAnotherOwnedHousehold(long owned) {
        return maxOwnedHouseholds == null || owned < maxOwnedHouseholds;
    }

    /**
     * Primer día visible en los listados: el día 1 del mes de hace {@code historyMonths - 1} meses.
     * Con 12 meses y hoy 2026-11-15, {@code 2025-12-01}.
     */
    @JsonIgnore
    public Optional<LocalDate> historyStart(LocalDate today) {
        if (historyMonths == null) {
            return Optional.empty();
        }
        // Una ventana que empezaría antes del año 1 no recorta nada (y PostgreSQL no admite esas fechas).
        long monthsSinceYearOne = (today.getYear() - 1L) * 12 + today.getMonthValue() - 1;
        if (historyMonths - 1L > monthsSinceYearOne) {
            return Optional.empty();
        }
        return Optional.of(today.withDayOfMonth(1).minusMonths(historyMonths - 1L));
    }

    /**
     * {@code from} efectivo de un listado: el más tardío entre el pedido y el inicio del historial
     * visible. {@code null} si no hay ninguno de los dos.
     */
    public LocalDate visibleFrom(LocalDate requestedFromOrNull, LocalDate today) {
        return historyStart(today)
                .filter(start -> requestedFromOrNull == null || start.isAfter(requestedFromOrNull))
                .orElse(requestedFromOrNull);
    }
}
