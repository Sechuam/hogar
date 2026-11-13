package com.hogar.user.dto;

/**
 * Resultado de entrar con Google.
 *
 * @param credentialsReset {@code true} si se vinculó a una cuenta con email sin verificar y por
 *                         eso se eliminó su contraseña: quien llama debe revocar todas sus sesiones
 */
public record GoogleSignIn(UserResponse user, boolean credentialsReset) {
}
