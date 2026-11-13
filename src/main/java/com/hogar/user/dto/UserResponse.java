package com.hogar.user.dto;

import java.util.List;
import java.util.UUID;

/**
 * Perfil del usuario autenticado.
 *
 * @param hasPassword     si puede entrar con email + contraseña
 * @param linkedProviders proveedores externos vinculados (p. ej. {@code GOOGLE})
 */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        boolean hasPassword,
        List<String> linkedProviders) {
}
