package com.hogar.household.config;

import com.hogar.household.dto.PlanLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Límites del plan FREE ({@code hogar.plans.free.*}). PREMIUM no tiene límites (D-068).
 *
 * @param maxMembers         miembros activos por hogar
 * @param maxOwnedHouseholds hogares FREE de los que un usuario puede ser OWNER
 * @param maxStorage         tamaño total de las facturas de un hogar
 * @param historyMonths      meses naturales visibles en listados de gastos y liquidaciones
 */
@ConfigurationProperties("hogar.plans.free")
public record PlanProperties(int maxMembers, int maxOwnedHouseholds, DataSize maxStorage, int historyMonths) {

    public PlanProperties {
        if (maxMembers < 1 || maxOwnedHouseholds < 1 || historyMonths < 1) {
            throw new IllegalArgumentException("hogar.plans.free: los límites deben ser >= 1");
        }
        if (maxStorage == null || maxStorage.isNegative()) {
            throw new IllegalArgumentException("hogar.plans.free.max-storage es obligatorio y no negativo");
        }
    }

    public PlanLimits freeLimits() {
        return new PlanLimits(maxMembers, maxOwnedHouseholds, maxStorage.toBytes(), historyMonths);
    }
}
