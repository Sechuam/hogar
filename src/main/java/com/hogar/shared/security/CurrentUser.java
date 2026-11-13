package com.hogar.shared.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Identidad del usuario autenticado en la petición actual ({@code sub} del access token).
 *
 * <p>Convención: solo los <b>controllers</b> lo usan y pasan el {@code userId} explícitamente
 * a los servicios, que así no dependen del contexto de seguridad y se testean sin él.
 */
@Component
public class CurrentUser {

    public UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return UUID.fromString(jwt.getToken().getSubject());
        }
        throw new IllegalStateException("No hay un usuario autenticado con JWT en el contexto");
    }
}
