package com.hogar.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hogar.auth.domain.RefreshToken;
import com.hogar.auth.repository.RefreshTokenRepository;
import com.hogar.shared.error.UnauthorizedException;
import com.hogar.shared.security.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");
    private static final Duration TTL = Duration.ofDays(30);
    private static final UUID USER = UUID.randomUUID();

    @Mock
    private RefreshTokenRepository tokens;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties("dGVzdC1vbmx5LXNlY3JldC1ub3QtZm9yLXByb2R1Y3Rpb24tMDEyMzQ1Njc4OWFiY2Q=",
                "hogar-test", Duration.ofMinutes(15), TTL);
        service = new RefreshTokenService(tokens, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issueStoresOnlyTheSha256OfARandomToken() {
        String raw = service.issue(USER);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(saved.capture());
        assertThat(raw).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(saved.getValue().getTokenHash()).isEqualTo(OpaqueTokens.sha256Hex(raw)).hasSize(64).isNotEqualTo(raw);
        assertThat(saved.getValue().getUserId()).isEqualTo(USER);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void rotateReplacesActiveTokenWithSuccessorInSameFamily() {
        RefreshToken current = RefreshToken.startFamily(USER, OpaqueTokens.sha256Hex("viejo"), NOW.minusSeconds(60), NOW.plus(TTL));
        when(tokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex("viejo"))).thenReturn(Optional.of(current));

        RefreshTokenService.Rotation rotation = service.rotate("viejo");

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(saved.capture());
        RefreshToken successor = saved.getValue();
        assertThat(rotation.userId()).isEqualTo(USER);
        assertThat(rotation.refreshToken()).isNotEqualTo("viejo");
        assertThat(successor.getTokenHash()).isEqualTo(OpaqueTokens.sha256Hex(rotation.refreshToken()));
        assertThat(successor.getFamilyId()).isEqualTo(current.getFamilyId());
        assertThat(current.isRevoked()).isTrue();
        assertThat(current.getReplacedById()).isEqualTo(successor.getId());
        verify(tokens, never()).revokeFamily(any(), any());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        RefreshToken rotated = RefreshToken.startFamily(USER, OpaqueTokens.sha256Hex("robado"), NOW.minusSeconds(60), NOW.plus(TTL));
        rotated.markReplacedBy(UUID.randomUUID(), NOW.minusSeconds(30));
        when(tokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex("robado"))).thenReturn(Optional.of(rotated));

        assertThatThrownBy(() -> service.rotate("robado"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("invalid-refresh-token");
        verify(tokens).revokeFamily(rotated.getFamilyId(), NOW);
        verify(tokens, never()).save(any());
    }

    @Test
    void expiredTokenIsRejectedWithoutRevokingFamily() {
        RefreshToken expired = RefreshToken.startFamily(USER, OpaqueTokens.sha256Hex("caducado"), NOW.minus(TTL), NOW);
        when(tokens.findByTokenHashForUpdate(OpaqueTokens.sha256Hex("caducado"))).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate("caducado"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("invalid-refresh-token");
        verify(tokens, never()).save(any());
        verify(tokens, never()).revokeFamily(any(), any());
    }

    @Test
    void unknownTokenIsRejected() {
        when(tokens.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("desconocido"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("invalid-refresh-token");
    }

    @Test
    void logoutRevokesFamilyAndIgnoresUnknownTokens() {
        RefreshToken token = RefreshToken.startFamily(USER, OpaqueTokens.sha256Hex("sesion"), NOW.minusSeconds(60), NOW.plus(TTL));
        when(tokens.findByTokenHash(OpaqueTokens.sha256Hex("sesion"))).thenReturn(Optional.of(token));
        when(tokens.findByTokenHash(OpaqueTokens.sha256Hex("otro"))).thenReturn(Optional.empty());

        service.revokeFamilyOf("sesion");
        service.revokeFamilyOf("otro");

        verify(tokens).revokeFamily(token.getFamilyId(), NOW);
    }

    @Test
    void sha256HexIsLowercaseHex() {
        assertThat(OpaqueTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
