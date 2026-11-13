package com.hogar.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(3600);
    private static final UUID USER = UUID.randomUUID();

    @Test
    void activeUntilExpiryExclusive() {
        RefreshToken token = RefreshToken.startFamily(USER, "h1", NOW, EXPIRES);

        assertThat(token.isActive(NOW)).isTrue();
        assertThat(token.isActive(EXPIRES.minusMillis(1))).isTrue();
        assertThat(token.isActive(EXPIRES)).isFalse();
        assertThat(token.isExpired(EXPIRES)).isTrue();
        assertThat(token.isExpired(NOW)).isFalse();
    }

    @Test
    void revokeIsIdempotent() {
        RefreshToken token = RefreshToken.startFamily(USER, "h1", NOW, EXPIRES);

        token.revoke(NOW.plusSeconds(1));
        token.revoke(NOW.plusSeconds(2));

        assertThat(token.isRevoked()).isTrue();
        assertThat(token.isActive(NOW.plusSeconds(3))).isFalse();
        assertThat(token.getRevokedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void rotationKeepsFamilyAndRevokesPrevious() {
        RefreshToken first = RefreshToken.startFamily(USER, "h1", NOW, EXPIRES);
        RefreshToken second = RefreshToken.rotateFrom(first, "h2", NOW.plusSeconds(10), EXPIRES.plusSeconds(10));

        first.markReplacedBy(second.getId(), NOW.plusSeconds(10));

        assertThat(second.getFamilyId()).isEqualTo(first.getFamilyId());
        assertThat(second.getUserId()).isEqualTo(USER);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(first.getReplacedById()).isEqualTo(second.getId());
        assertThat(first.isActive(NOW.plusSeconds(11))).isFalse();
        assertThat(second.isActive(NOW.plusSeconds(11))).isTrue();
    }

    @Test
    void newFamiliesAreDistinct() {
        assertThat(RefreshToken.startFamily(USER, "a", NOW, EXPIRES).getFamilyId())
                .isNotEqualTo(RefreshToken.startFamily(USER, "b", NOW, EXPIRES).getFamilyId());
    }

    @Test
    void expiryMustBeAfterCreation() {
        assertThatThrownBy(() -> RefreshToken.startFamily(USER, "h", NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
