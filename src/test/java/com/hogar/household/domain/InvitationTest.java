package com.hogar.household.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(3600);
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void expiresAtExpiryInstant() {
        Invitation invitation = Invitation.create(HOUSEHOLD, "CODE123456", OWNER, NOW, EXPIRES, null);

        assertThat(invitation.isExpired(EXPIRES.minusMillis(1))).isFalse();
        assertThat(invitation.isExpired(EXPIRES)).isTrue();
    }

    @Test
    void limitedInvitationIsExhaustedAfterMaxUses() {
        Invitation invitation = Invitation.create(HOUSEHOLD, "CODE123456", OWNER, NOW, EXPIRES, 2);

        invitation.registerUse();
        assertThat(invitation.isExhausted()).isFalse();
        invitation.registerUse();

        assertThat(invitation.isExhausted()).isTrue();
        assertThat(invitation.getUses()).isEqualTo(2);
        assertThatThrownBy(invitation::registerUse).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unlimitedInvitationIsNeverExhausted() {
        Invitation invitation = Invitation.create(HOUSEHOLD, "CODE123456", OWNER, NOW, EXPIRES, null);

        for (int i = 0; i < 100; i++) {
            invitation.registerUse();
        }

        assertThat(invitation.isExhausted()).isFalse();
    }

    @Test
    void revokeIsIdempotent() {
        Invitation invitation = Invitation.create(HOUSEHOLD, "CODE123456", OWNER, NOW, EXPIRES, null);

        invitation.revoke(NOW.plusSeconds(1));
        invitation.revoke(NOW.plusSeconds(2));

        assertThat(invitation.isRevoked()).isTrue();
        assertThat(invitation.getRevokedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void validatesArguments() {
        assertThatThrownBy(() -> Invitation.create(HOUSEHOLD, "", OWNER, NOW, EXPIRES, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Invitation.create(HOUSEHOLD, "X".repeat(17), OWNER, NOW, EXPIRES, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Invitation.create(HOUSEHOLD, "CODE", OWNER, NOW, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Invitation.create(HOUSEHOLD, "CODE", OWNER, NOW, EXPIRES, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
