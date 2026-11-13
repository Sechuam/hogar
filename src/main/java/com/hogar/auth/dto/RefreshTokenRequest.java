package com.hogar.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code /api/auth/refresh} y {@code /api/auth/logout}. */
public record RefreshTokenRequest(@NotBlank @Size(max = 256) String refreshToken) {
}
