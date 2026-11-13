package com.hogar.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.support.AbstractIntegrationTest;
import com.hogar.user.domain.IdentityProvider;
import com.hogar.user.domain.User;
import com.hogar.user.domain.UserIdentity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class UserRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");

    @Autowired
    private UserRepository users;

    @Autowired
    private UserIdentityRepository identities;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliedAllPhaseOneMigrations() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version in ('2','3','4','5')",
                Integer.class);

        assertThat(applied).isEqualTo(4);
    }

    @Test
    void savesUserWithNormalizedEmailAndFindsItByEmail() {
        User saved = users.saveAndFlush(User.registerWithPassword("  Ana@Example.COM ", "Ana", "hash", NOW));
        em.clear();

        User found = users.findByEmail("ana@example.com").orElseThrow();

        assertThat(found).isEqualTo(saved);
        assertThat(found.getEmail()).isEqualTo("ana@example.com");
        assertThat(found.hasPassword()).isTrue();
        assertThat(found.getCreatedAt()).isEqualTo(NOW);
        assertThat(found.getVersion()).isZero();
        assertThat(users.existsByEmail("ana@example.com")).isTrue();
    }

    @Test
    void emailIsUniqueRegardlessOfOriginalCase() {
        users.saveAndFlush(User.registerWithPassword("bob@example.com", "Bob", "hash", NOW));

        assertThatThrownBy(() -> users.saveAndFlush(User.registerExternal("BOB@example.com", "Bobby", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNonLowercaseEmail() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into users (id, email, display_name, created_at, updated_at) values (?, ?, ?, now(), now())",
                UUID.randomUUID(), "Upper@Example.com", "X"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsIdentitiesInCascadeAndFindsThemByProviderAndSubject() {
        User user = User.registerExternal("carla@example.com", "Carla", NOW);
        user.linkIdentity(IdentityProvider.GOOGLE, "google-sub-1", "Carla@Gmail.com", NOW);
        users.saveAndFlush(user);
        em.clear();

        UserIdentity identity = identities.findByProviderAndSubject(IdentityProvider.GOOGLE, "google-sub-1")
                .orElseThrow();

        assertThat(identity.getUser().getId()).isEqualTo(user.getId());
        assertThat(identity.getEmail()).isEqualTo("carla@gmail.com");
        assertThat(users.findById(user.getId()).orElseThrow().getIdentities()).containsExactly(identity);
        assertThat(identities.findByProviderAndSubject(IdentityProvider.GOOGLE, "other")).isEmpty();
    }

    @Test
    void providerSubjectPairIsUnique() {
        User first = User.registerExternal("dani@example.com", "Dani", NOW);
        first.linkIdentity(IdentityProvider.GOOGLE, "same-sub", "dani@example.com", NOW);
        users.saveAndFlush(first);

        User second = User.registerExternal("eva@example.com", "Eva", NOW);
        second.linkIdentity(IdentityProvider.GOOGLE, "same-sub", "eva@example.com", NOW);

        assertThatThrownBy(() -> users.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updatesBumpVersionAndUpdatedAt() {
        User user = users.saveAndFlush(User.registerWithPassword("fede@example.com", "Fede", "hash", NOW));
        Instant later = NOW.plusSeconds(60);

        user.changeDisplayName("Federico", later);
        users.saveAndFlush(user);
        em.clear();

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("Federico");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(later);
        assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }
}
