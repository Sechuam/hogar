package com.hogar.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code /api/auth/refresh} y {@code /api/auth/logout}. */
public record RefreshTokenRequest(@Schema(description = "Refresh token recibido en el último login o refresh.", example = "Qk9xY2V0dXJhLXJlZnJlc2gtdG9rZW4tZGUtZWplbXBsbw") @NotBlank @Size(max = 256) String refreshToken) {
}
