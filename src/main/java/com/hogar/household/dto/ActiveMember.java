package com.hogar.household.dto;

import com.hogar.household.domain.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/** Miembro activo de un hogar, tal como lo necesitan otras features (reparto, tareas). */
public record ActiveMember(UUID userId, HouseholdRole role, Instant joinedAt) {
}
