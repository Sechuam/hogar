package com.hogar.household.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * @param ttlHours horas de validez; opcional (por defecto {@code hogar.invitations.default-ttl}),
 *                 nunca mayor que {@code hogar.invitations.max-ttl}
 * @param maxUses  usos máximos; opcional ({@code null} = ilimitado hasta caducar)
 */
public record CreateInvitationRequest(
        @Min(1) @Max(720) Integer ttlHours,
        @Min(1) @Max(50) Integer maxUses) {
}
