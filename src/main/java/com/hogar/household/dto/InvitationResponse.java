package com.hogar.household.dto;

import java.time.Instant;
import java.util.UUID;

public record InvitationResponse(
        UUID id,
        String code,
        Instant createdAt,
        Instant expiresAt,
        Integer maxUses,
        int uses,
        boolean revoked) {
}
