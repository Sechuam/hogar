package com.hogar.user.dto;

import java.util.UUID;

/** Vista mínima de un usuario para otras features (p. ej. listado de miembros). */
public record UserSummary(UUID id, String displayName, String email) {
}
