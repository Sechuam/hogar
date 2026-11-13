package com.hogar.auth.service;

import com.hogar.auth.domain.RefreshToken;
import com.hogar.auth.repository.RefreshTokenRepository;
import com.hogar.shared.error.UnauthorizedException;
import com.hogar.shared.security.JwtProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh tokens opacos y rotatorios. El valor en claro solo existe en la respuesta al cliente;
 * en BD se guarda su SHA-256.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    static final String INVALID_REFRESH_TOKEN = "invalid-refresh-token";

    private final RefreshTokenRepository tokens;
    private final JwtProperties props;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, JwtProperties props, Clock clock) {
        this.tokens = tokens;
        this.props = props;
        this.clock = clock;
    }

    /** Resultado de una rotación: dueño del token y nuevo valor en claro. */
    public record Rotation(UUID userId, String refreshToken) {
    }

    /** Abre una sesión (familia nueva) y devuelve el token en claro. */
    @Transactional
    public String issue(UUID userId) {
        Instant now = Instant.now(clock);
        String raw = OpaqueTokens.generate();
        tokens.save(RefreshToken.startFamily(userId, OpaqueTokens.sha256Hex(raw), now, expiry(now)));
        return raw;
    }

    /**
     * Cambia un token activo por uno nuevo de la misma familia. Un token desconocido o caducado
     * se rechaza; uno ya rotado o revocado es una <b>reutilización</b> (posible robo): se revoca
     * la familia entera, incluido el sucesor que tenga el atacante o el cliente legítimo.
     *
     * <p>{@code noRollbackFor}: la revocación de la familia debe confirmarse aunque se lance el 401.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String rawToken) {
        RefreshToken current = tokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex(rawToken))
                .orElseThrow(RefreshTokenService::invalidToken);
        Instant now = Instant.now(clock);
        if (current.isRevoked()) {
            int revoked = tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Reutilización de refresh token detectada: familia {} revocada ({} tokens activos), usuario {}",
                    current.getFamilyId(), revoked, current.getUserId());
            throw invalidToken();
        }
        if (current.isExpired(now)) {
            throw invalidToken();
        }
        String raw = OpaqueTokens.generate();
        RefreshToken successor = RefreshToken.rotateFrom(current, OpaqueTokens.sha256Hex(raw), now, expiry(now));
        tokens.save(successor);
        current.markReplacedBy(successor.getId(), now);
        return new Rotation(current.getUserId(), raw);
    }

    /** Cierra la sesión revocando la familia del token. Token desconocido → no hace nada. */
    /** Revoca todas las sesiones del usuario (p. ej. al invalidar sus credenciales). */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        int revoked = tokens.revokeAllByUserId(userId, Instant.now(clock));
        log.info("Revocados {} refresh tokens del usuario {}", revoked, userId);
    }

    @Transactional
    public void revokeFamilyOf(String rawToken) {
        tokens.findByTokenHash(OpaqueTokens.sha256Hex(rawToken))
                .ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), Instant.now(clock)));
    }

    private Instant expiry(Instant now) {
        return now.plus(props.refreshTokenTtl());
    }

    private static UnauthorizedException invalidToken() {
        return new UnauthorizedException(INVALID_REFRESH_TOKEN, "El refresh token no es válido.");
    }
}
