package com.hogar.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hogar.shared.error.ConflictException;
import com.hogar.user.domain.IdentityProvider;
import com.hogar.user.domain.User;
import com.hogar.user.domain.UserIdentity;
import com.hogar.user.dto.UserResponse;
import com.hogar.user.repository.UserIdentityRepository;
import com.hogar.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");
    private static final String DUMMY_HASH = "$2a$10$dummy";

    @Mock
    private UserRepository users;

    @Mock
    private UserIdentityRepository identities;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserAccountService service;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        service = new UserAccountService(users, identities, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registerHashesPasswordAndNormalizesEmail() {
        when(passwordEncoder.encode("secreto-123")).thenReturn("$2a$10$hash");

        UserResponse response = service.registerWithPassword("  Ana@Example.COM ", "secreto-123", " Ana ");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ana@example.com");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$hash");
        assertThat(saved.getValue().isEmailVerified()).isFalse();
        assertThat(response.email()).isEqualTo("ana@example.com");
        assertThat(response.displayName()).isEqualTo("Ana");
        assertThat(response.hasPassword()).isTrue();
        assertThat(response.linkedProviders()).isEmpty();
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(users.existsByEmail("ana@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.registerWithPassword("ANA@example.com", "secreto-123", "Ana"))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("email-already-registered");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void registerRaceOnUniqueEmailIsAConflictNotAServerError() {
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> service.registerWithPassword("ana@example.com", "secreto-123", "Ana"))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("email-already-registered");
    }

    @Test
    void authenticateSucceedsWithRightPassword() {
        User user = passwordUser("ana@example.com");
        when(users.findByEmail("ana@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secreto-123", "$2a$10$real")).thenReturn(true);

        assertThat(service.authenticate(" ANA@example.com", "secreto-123"))
                .hasValueSatisfying(r -> assertThat(r.id()).isEqualTo(user.getId()));
    }

    @Test
    void authenticateFailsWithWrongPassword() {
        when(users.findByEmail("ana@example.com")).thenReturn(Optional.of(passwordUser("ana@example.com")));
        when(passwordEncoder.matches("mala", "$2a$10$real")).thenReturn(false);

        assertThat(service.authenticate("ana@example.com", "mala")).isEmpty();
    }

    @Test
    void authenticateUnknownEmailStillRunsBcryptAgainstDummyHash() {
        when(users.findByEmail("nadie@example.com")).thenReturn(Optional.empty());

        assertThat(service.authenticate("nadie@example.com", "lo-que-sea")).isEmpty();
        verify(passwordEncoder).matches("lo-que-sea", DUMMY_HASH);
    }

    @Test
    void authenticateGoogleOnlyUserFailsWithSameCost() {
        when(users.findByEmail("g@example.com"))
                .thenReturn(Optional.of(User.registerExternal("g@example.com", "G", NOW)));
        // Aunque el dummy "coincidiera", un usuario sin contraseña nunca entra por aquí.
        when(passwordEncoder.matches("x", DUMMY_HASH)).thenReturn(true);

        assertThat(service.authenticate("g@example.com", "x")).isEmpty();
        verify(passwordEncoder).matches("x", DUMMY_HASH);
    }

    @Test
    void googleSignInWithLinkedSubjectEntersThatUserEvenIfEmailChanged() {
        User user = User.registerExternal("antiguo@gmail.com", "Ana", NOW);
        UserIdentity identity = user.linkIdentity(IdentityProvider.GOOGLE, "sub-1", "antiguo@gmail.com", NOW);
        when(identities.findByProviderAndSubject(IdentityProvider.GOOGLE, "sub-1")).thenReturn(Optional.of(identity));

        UserResponse response = service.signInWithGoogle("sub-1", "nuevo@gmail.com", "Ana");

        assertThat(response.id()).isEqualTo(user.getId());
        assertThat(response.linkedProviders()).containsExactly("GOOGLE");
        verify(users, never()).findByEmail(anyString());
        verify(users, never()).save(any());
    }

    @Test
    void googleSignInLinksToExistingAccountWithSameEmail() {
        User user = passwordUser("ana@example.com");
        when(identities.findByProviderAndSubject(IdentityProvider.GOOGLE, "sub-2")).thenReturn(Optional.empty());
        when(users.findByEmail("ana@example.com")).thenReturn(Optional.of(user));

        UserResponse response = service.signInWithGoogle("sub-2", "Ana@Example.com", "Ana G");

        assertThat(response.id()).isEqualTo(user.getId());
        assertThat(response.hasPassword()).isTrue();
        assertThat(response.linkedProviders()).containsExactly("GOOGLE");
        assertThat(user.getIdentities()).extracting(UserIdentity::getSubject).containsExactly("sub-2");
        assertThat(user.isEmailVerified()).isTrue();
        verify(users, never()).save(any());
    }

    @Test
    void googleSignInCreatesPasswordlessUserWhenNothingMatches() {
        when(identities.findByProviderAndSubject(IdentityProvider.GOOGLE, "sub-3")).thenReturn(Optional.empty());
        when(users.findByEmail("nueva@gmail.com")).thenReturn(Optional.empty());

        UserResponse response = service.signInWithGoogle("sub-3", "nueva@gmail.com", "Nueva");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User user = saved.getValue();
        assertThat(user.hasPassword()).isFalse();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getIdentities()).extracting(UserIdentity::getSubject).containsExactly("sub-3");
        assertThat(response).isEqualTo(new com.hogar.user.dto.UserResponse(
                user.getId(), "nueva@gmail.com", "Nueva", false, List.of("GOOGLE")));
    }

    private static User passwordUser(String email) {
        return User.registerWithPassword(email, "Ana", "$2a$10$real", NOW);
    }
}
