package com.hogar.user.service;

import com.hogar.user.dto.GoogleSignIn;
import com.hogar.user.dto.UserResponse;
import java.util.Optional;
import java.util.UUID;

/**
 * Contrato público de la feature user para gestionar cuentas (alta, credenciales, identidades
 * externas y perfil). Lo usa la feature {@code auth}, que nunca toca entidades ni repositorios
 * de {@code user}.
 */
public interface UserAccounts {

    /**
     * Alta con email y contraseña (la contraseña se guarda con BCrypt).
     *
     * @throws com.hogar.shared.error.ConflictException {@code email-already-registered}
     */
    UserResponse registerWithPassword(String email, String rawPassword, String displayName);

    /**
     * Comprueba email + contraseña. Vacío si el email no existe, el usuario no tiene contraseña
     * (solo Google) o la contraseña no coincide; en todos los casos se ejecuta un BCrypt para que
     * el tiempo de respuesta no revele si el email existe.
     */
    Optional<UserResponse> authenticate(String email, String rawPassword);

    /**
     * Entrada con una identidad de Google <b>ya verificada</b> (firma, {@code aud}, {@code iss},
     * {@code exp} y {@code email_verified}). Orden de resolución: por {@code subject} vinculado →
     * por email de una cuenta existente (se vincula) → alta nueva sin contraseña.
     */
    GoogleSignIn signInWithGoogle(String subject, String email, String displayName);

    /** Perfil del usuario, si existe. */
    Optional<UserResponse> findProfile(UUID userId);
}
