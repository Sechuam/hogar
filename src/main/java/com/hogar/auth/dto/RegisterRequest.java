package com.hogar.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import com.hogar.shared.validation.HasText;
import com.hogar.shared.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registro con email y contraseña. La contraseña se limita a 72 bytes en UTF-8 (72 caracteres
 * ASCII, menos si lleva acentos o eñes) porque es el máximo de BCrypt.
 */
public record RegisterRequest(
        @Schema(description = "Email (se guarda en minúsculas).", example = "ana@example.com")
        @NotBlank @Email @Size(max = 254) String email,
        @Schema(description = "Contraseña de 8 a 72 caracteres y como mucho 72 bytes en UTF-8 (límite de BCrypt).",
                example = "correct-horse-battery")
        @NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String password,
        @Schema(description = "Nombre visible para el resto de miembros.", example = "Ana")
        @NotNull @HasText @Size(max = 100) String displayName) {
}
