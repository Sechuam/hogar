package com.hogar.household.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Invitación a un hogar.")
public record InvitationResponse(
        @Schema(description = "ID de la invitación.", example = "3f6c1d2e-8a4b-4c5d-9e7f-0a1b2c3d4e5f")
        UUID id,
        @Schema(description = "Código para compartir (10 caracteres Crockford Base32).", example = "K7M2Q9XW4P")
        String code,
        @Schema(description = "Creación (UTC).", example = "2026-03-15T09:30:00Z")
        Instant createdAt,
        @Schema(description = "Caducidad (UTC).", example = "2026-03-17T09:30:00Z")
        Instant expiresAt,
        @Schema(description = "Usos máximos; null = ilimitado.", example = "3")
        Integer maxUses,
        @Schema(description = "Veces que se ha aceptado.", example = "1")
        int uses,
        @Schema(description = "Si un OWNER la revocó.", example = "false")
        boolean revoked) {
}
