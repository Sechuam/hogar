package com.hogar.household.dto;

import com.hogar.household.domain.SplitPolicy;
import java.util.List;
import java.util.UUID;

/**
 * Lo que la feature de gastos necesita saber de un hogar en el momento de repartir un gasto.
 *
 * @param activeMembers miembros activos ordenados por fecha de alta (luego por userId)
 */
public record HouseholdSplitInfo(UUID householdId, SplitPolicy splitPolicy, String currency,
                                 List<ActiveMember> activeMembers) {

    public boolean isActiveMember(UUID userId) {
        return activeMembers.stream().anyMatch(m -> m.userId().equals(userId));
    }
}
