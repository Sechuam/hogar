package com.hogar.household.service;

import com.hogar.household.dto.HouseholdSplitInfo;
import java.util.UUID;

/**
 * Contrato público de solo lectura de la feature household para otras features.
 * No comprueba permisos: quien llama ya ha pasado por {@link com.hogar.shared.security.MembershipGuard}.
 */
public interface HouseholdQueries {

    /** @throws com.hogar.shared.error.NotFoundException {@code household-not-found} si no existe */
    HouseholdSplitInfo getSplitInfo(UUID householdId);
}
