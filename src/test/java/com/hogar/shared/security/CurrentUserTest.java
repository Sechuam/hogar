package com.hogar.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void idIsTheSubjectOfTheAccessToken() {
        UUID id = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(id.toString())
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThat(currentUser.id()).isEqualTo(id);
    }

    /** Fallar cerrado: otra forma de autenticación (o ninguna) nunca se traduce en un id de usuario. */
    @Test
    void anythingButAJwtIsRejected() {
        assertThatThrownBy(currentUser::id).isInstanceOf(IllegalStateException.class);

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(UUID.randomUUID().toString(), null, List.of()));
        assertThatThrownBy(currentUser::id).isInstanceOf(IllegalStateException.class);
    }
}
