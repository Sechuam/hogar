package com.hogar.auth.service;

/**
 * Verifica un ID token de Google (firma, {@code iss}, {@code aud}, {@code exp}) y extrae la
 * identidad. Interfaz para poder sustituirla en los tests de integración.
 */
public interface GoogleIdTokenVerifier {

    /**
     * @throws com.hogar.shared.error.UnauthorizedException {@code invalid-google-token} si el
     *         token no es válido. Que el email esté verificado lo decide quien llama.
     */
    GoogleIdentity verify(String idToken);

    /** Datos del ID token que usa la aplicación. {@code name} puede ser {@code null}. */
    record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {
    }
}
