package com.hogar.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Schema(description = "Email de la cuenta (no distingue mayúsculas).", example = "ana@example.com")
        @NotBlank @Size(max = 254) String email,
        @Schema(description = "Contraseña.", example = "correct-horse-battery")
        @NotBlank @Size(max = 72) String password) {
}
