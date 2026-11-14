package com.hogar.household.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * @param ttlHours horas de validez; opcional (por defecto {@code hogar.invitations.default-ttl}),
 *                 nunca mayor que {@code hogar.invitations.max-ttl}
 * @param maxUses  usos máximos; opcional ({@code null} = ilimitado hasta caducar)
 */
public record CreateInvitationRequest(
        @Schema(description = "Horas de validez (1–720); opcional, por defecto la configurada en el servidor.",
                example = "48")
        @Min(1) @Max(720) Integer ttlHours,
        @Schema(description = "Usos máximos (1–50); opcional, ausente = ilimitado hasta caducar.", example = "3")
        @Min(1) @Max(50) Integer maxUses) {
}
