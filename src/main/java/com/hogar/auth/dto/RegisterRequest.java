package com.hogar.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import com.hogar.shared.validation.HasText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registro con email y contraseña. La contraseña se limita a 72 caracteres porque BCrypt
 * ignora lo que pasa de 72 bytes.
 */
public record RegisterRequest(
        @Schema(description = "Email (se guarda en minúsculas).", example = "ana@example.com")
        @NotBlank @Email @Size(max = 254) String email,
        @Schema(description = "Contraseña de 8 a 72 caracteres.", example = "correct-horse-battery")
        @NotBlank @Size(min = 8, max = 72) String password,
        @Schema(description = "Nombre visible para el resto de miembros.", example = "Ana")
        @NotNull @HasText @Size(max = 100) String displayName) {
}
