package com.hogar.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Perfil del usuario autenticado.
 *
 * @param hasPassword     si puede entrar con email + contraseña
 * @param linkedProviders proveedores externos vinculados (p. ej. {@code GOOGLE})
 */
@Schema(description = "Perfil de un usuario.")
public record UserResponse(
        @Schema(description = "ID del usuario.", example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        UUID id,
        @Schema(description = "Email (en minúsculas).", example = "ana@example.com")
        String email,
        @Schema(description = "Nombre visible.", example = "Ana")
        String displayName,
        @Schema(description = "Si puede entrar con email y contraseña (`false` = solo Google).", example = "true")
        boolean hasPassword,
        @Schema(description = "Proveedores externos vinculados.", allowableValues = {"GOOGLE"})
        List<String> linkedProviders) {
}
