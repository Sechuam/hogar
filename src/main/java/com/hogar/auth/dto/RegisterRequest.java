package com.hogar.auth.dto;

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
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull @HasText @Size(max = 100) String displayName) {
}
