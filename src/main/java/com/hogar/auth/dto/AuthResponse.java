package com.hogar.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.hogar.user.dto.UserResponse;

/**
 * Par de tokens emitido en registro, login, Google y refresh.
 *
 * @param expiresIn segundos de vida del access token
 */
@Schema(description = "Sesión abierta: par de tokens y usuario.")
public record AuthResponse(
        @Schema(description = "Access token JWT (HS256). Se envía como `Authorization: Bearer <token>`.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3YjJlNGMxYSJ9.c2lnbmF0dXJh")
        String accessToken,
        @Schema(description = "Siempre `Bearer`.", example = "Bearer")
        String tokenType,
        @Schema(description = "Segundos de vida del access token.", example = "900")
        long expiresIn,
        @Schema(description = "Refresh token opaco de un solo uso (se rota en cada `/refresh`).",
                example = "Qk9xY2V0dXJhLXJlZnJlc2gtdG9rZW4tZGUtZWplbXBsbw")
        String refreshToken,
        @Schema(description = "Usuario de la sesión.")
        UserResponse user) {

    public static AuthResponse bearer(String accessToken, long expiresIn, String refreshToken, UserResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresIn, refreshToken, user);
    }
}
