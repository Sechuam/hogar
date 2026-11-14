package com.hogar.household.repository;

import java.util.UUID;

/** Proyección: miembros activos de un hogar (resultado de un {@code group by household_id}). */
public record ActiveMemberCount(UUID householdId, long activeMembers) {
}
