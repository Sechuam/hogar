package com.hogar.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");

    @Test
    void normalizesEmailToTrimmedLowercase() {
        User user = User.registerWithPassword("  Ana.Perez@Example.COM ", "Ana", "hash", NOW);

        assertThat(user.getEmail()).isEqualTo("ana.perez@example.com");
        assertThat(user.getId()).isNotNull();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.hasPassword()).isTrue();
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void externalUserHasNoPasswordAndVerifiedEmail() {
        User user = User.registerExternal("bob@example.com", "Bob", NOW);

        assertThat(user.hasPassword()).isFalse();
        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    void rejectsBlankFields() {
        assertThatThrownBy(() -> User.registerExternal(" ", "Bob", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.registerExternal("bob@example.com", "", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.registerWithPassword("bob@example.com", "Bob", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linkIdentityAddsIdentityAndTouchesUser() {
        User user = User.registerWithPassword("carla@example.com", "Carla", "hash", NOW);
        Instant later = NOW.plusSeconds(5);

        UserIdentity identity = user.linkIdentity(IdentityProvider.GOOGLE, "sub-1", "Carla@Gmail.com", later);

        assertThat(user.getIdentities()).containsExactly(identity);
        assertThat(identity.getUser()).isSameAs(user);
        assertThat(identity.getEmail()).isEqualTo("carla@gmail.com");
        assertThat(identity.isNew()).isTrue();
        assertThat(user.getUpdatedAt()).isEqualTo(later);
        assertThatThrownBy(() -> user.getIdentities().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutatorsUpdateTimestamp() {
        User user = User.registerWithPassword("dani@example.com", "Dani", "hash", NOW);

        user.markEmailVerified(NOW.plusSeconds(1));
        user.markEmailVerified(NOW.plusSeconds(2));
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));

        user.changePasswordHash("new-hash", NOW.plusSeconds(3));
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW.plusSeconds(3));
    }

    @Test
    void equalityIsById() {
        User a = User.registerExternal("eva@example.com", "Eva", NOW);
        User b = User.registerExternal("eva@example.com", "Eva", NOW);

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(a);
    }
}
