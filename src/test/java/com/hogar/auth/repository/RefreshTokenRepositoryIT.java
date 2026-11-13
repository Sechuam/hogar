package com.hogar.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.auth.domain.RefreshToken;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.user.domain.User;
import com.hogar.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RefreshTokenRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");
    private static final Instant EXPIRES = NOW.plus(Duration.ofDays(30));

    @Autowired
    private RefreshTokenRepository tokens;

    @Autowired
    private UserRepository users;

    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = users.saveAndFlush(
                User.registerWithPassword("tokens-" + UUID.randomUUID() + "@example.com", "T", "hash", NOW)).getId();
    }

    @Test
    void findsByTokenHash() {
        RefreshToken token = tokens.saveAndFlush(RefreshToken.startFamily(userId, hash('a'), NOW, EXPIRES));

        assertThat(tokens.findByTokenHash(hash('a'))).contains(token);
        assertThat(tokens.findByTokenHash(hash('b'))).isEmpty();
    }

    @Test
    void tokenHashIsUnique() {
        tokens.saveAndFlush(RefreshToken.startFamily(userId, hash('c'), NOW, EXPIRES));

        assertThatThrownBy(() -> tokens.saveAndFlush(RefreshToken.startFamily(userId, hash('c'), NOW, EXPIRES)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revokeFamilyRevokesOnlyActiveTokensOfThatFamily() {
        RefreshToken first = RefreshToken.startFamily(userId, hash('1'), NOW, EXPIRES);
        RefreshToken second = RefreshToken.rotateFrom(first, hash('2'), NOW.plusSeconds(10), EXPIRES);
        first.markReplacedBy(second.getId(), NOW.plusSeconds(10));
        RefreshToken otherFamily = RefreshToken.startFamily(userId, hash('3'), NOW, EXPIRES);
        tokens.saveAllAndFlush(List.of(first, second, otherFamily));
        Instant reuseDetectedAt = NOW.plusSeconds(20);

        int revoked = tokens.revokeFamily(first.getFamilyId(), reuseDetectedAt);

        assertThat(revoked).isEqualTo(1);
        RefreshToken reloadedFirst = tokens.findById(first.getId()).orElseThrow();
        assertThat(reloadedFirst.getRevokedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(reloadedFirst.getReplacedById()).isEqualTo(second.getId());
        assertThat(tokens.findById(second.getId()).orElseThrow().getRevokedAt()).isEqualTo(reuseDetectedAt);
        assertThat(tokens.findById(otherFamily.getId()).orElseThrow().isActive(reuseDetectedAt)).isTrue();
    }

    @Test
    void revokeAllByUserIdRevokesEveryFamilyOfTheUser() {
        tokens.saveAndFlush(RefreshToken.startFamily(userId, hash('4'), NOW, EXPIRES));
        tokens.saveAndFlush(RefreshToken.startFamily(userId, hash('5'), NOW, EXPIRES));

        assertThat(tokens.revokeAllByUserId(userId, NOW.plusSeconds(1))).isEqualTo(2);
        assertThat(tokens.findAll()).filteredOn(t -> t.getUserId().equals(userId)).allMatch(RefreshToken::isRevoked);
    }

    /** SHA-256 hex ficticio de 64 caracteres. */
    private static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }
}
