package com.hogar.user.service;

import com.hogar.user.dto.UserSummary;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Contrato público de la feature user para el resto de features. */
public interface UserDirectory {

    /** Resúmenes de los usuarios pedidos; los IDs inexistentes simplemente no aparecen. */
    Map<UUID, UserSummary> findSummaries(Collection<UUID> userIds);
}
