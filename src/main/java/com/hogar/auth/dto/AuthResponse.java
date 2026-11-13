package com.hogar.auth.dto;

import com.hogar.user.dto.UserResponse;

/**
 * Par de tokens emitido en registro, login, Google y refresh.
 *
 * @param expiresIn segundos de vida del access token
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        UserResponse user) {

    public static AuthResponse bearer(String accessToken, long expiresIn, String refreshToken, UserResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresIn, refreshToken, user);
    }
}
