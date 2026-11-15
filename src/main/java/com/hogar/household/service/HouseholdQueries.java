package com.hogar.household.service;

import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.dto.PlanLimits;
import java.util.UUID;

/**
 * Contrato público de solo lectura de la feature household para otras features.
 * No comprueba permisos: quien llama ya ha pasado por {@link com.hogar.shared.security.MembershipGuard}.
 */
public interface HouseholdQueries {

    /** @throws com.hogar.shared.error.NotFoundException {@code household-not-found} si no existe */
    HouseholdSplitInfo getSplitInfo(UUID householdId);

    /**
     * {@code true} si el usuario es o ha sido miembro del hogar (también si lo dejó o lo expulsaron):
     * sus deudas siguen siendo del hogar.
     */
    boolean isCurrentOrFormerMember(UUID householdId, UUID userId);

    /**
     * Límites del plan del hogar (D-068).
     *
     * @throws com.hogar.shared.error.NotFoundException {@code household-not-found} si no existe
     */
    PlanLimits planLimits(UUID householdId);

    /**
     * Como {@link #planLimits} pero bloqueando la fila del hogar ({@code FOR UPDATE}) hasta el fin de
     * la transacción del llamante, para que dos operaciones no superen a la vez un límite de uso.
     * Exige una transacción activa.
     */
    PlanLimits lockPlanUsage(UUID householdId);
}
