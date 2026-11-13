package com.hogar.user.service;

import com.hogar.shared.error.ConflictException;
import com.hogar.user.domain.IdentityProvider;
import com.hogar.user.domain.User;
import com.hogar.user.domain.UserIdentity;
import com.hogar.user.dto.GoogleSignIn;
import com.hogar.user.dto.UserResponse;
import com.hogar.user.repository.UserIdentityRepository;
import com.hogar.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserAccountService implements UserAccounts {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    static final String EMAIL_ALREADY_REGISTERED = "email-already-registered";

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    /**
     * Hash BCrypt de una contraseña aleatoria, calculado una vez. Se compara contra él cuando el
     * usuario no existe o no tiene contraseña, para igualar el coste del login fallido.
     */
    private final String dummyPasswordHash;

    UserAccountService(UserRepository users, UserIdentityRepository identities,
                       PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.identities = identities;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public UserResponse registerWithPassword(String email, String rawPassword, String displayName) {
        String normalized = User.normalizeEmail(email);
        if (users.existsByEmail(normalized)) {
            throw emailAlreadyRegistered();
        }
        User user = User.registerWithPassword(normalized, displayName.trim(),
                passwordEncoder.encode(rawPassword), Instant.now(clock));
        try {
            // flush para que una carrera con otro alta del mismo email salga como 409 y no como 500.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailAlreadyRegistered();
        }
        log.info("Usuario registrado con contraseña: {}", user.getId());
        return toResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserResponse> authenticate(String email, String rawPassword) {
        Optional<User> user = users.findByEmail(User.normalizeEmail(email)).filter(User::hasPassword);
        String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        return user.filter(u -> matches).map(UserAccountService::toResponse);
    }

    @Override
    @Transactional
    public GoogleSignIn signInWithGoogle(String subject, String email, String displayName) {
        Optional<UserIdentity> linked = identities.findByProviderAndSubject(IdentityProvider.GOOGLE, subject);
        if (linked.isPresent()) {
            return new GoogleSignIn(toResponse(linked.get().getUser()), false);
        }
        Instant now = Instant.now(clock);
        String normalized = User.normalizeEmail(email);
        Optional<User> existing = users.findByEmail(normalized);
        if (existing.isPresent()) {
            User user = existing.get();
            // Pre-secuestro de cuenta: alguien pudo registrarse con contraseña usando este email
            // sin poseerlo. Si el email nunca se verificó, Google acredita ahora al dueño real y
            // la contraseña previa deja de ser de confianza: se elimina (y el llamante revoca sesiones).
            boolean credentialsReset = !user.isEmailVerified() && user.hasPassword();
            if (credentialsReset) {
                user.removePassword(now);
                log.warn("Contraseña eliminada al vincular Google a la cuenta no verificada {}", user.getId());
            }
            user.linkIdentity(IdentityProvider.GOOGLE, subject, normalized, now);
            user.markEmailVerified(now);
            log.info("Identidad de Google vinculada a la cuenta existente {}", user.getId());
            return new GoogleSignIn(toResponse(user), credentialsReset);
        }
        User user = User.registerExternal(normalized, displayName, now);
        user.linkIdentity(IdentityProvider.GOOGLE, subject, normalized, now);
        users.save(user);
        log.info("Usuario registrado con Google: {}", user.getId());
        return new GoogleSignIn(toResponse(user), false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserResponse> findProfile(UUID userId) {
        return users.findById(userId).map(UserAccountService::toResponse);
    }

    private static ConflictException emailAlreadyRegistered() {
        return new ConflictException(EMAIL_ALREADY_REGISTERED, "Ya existe una cuenta con ese email.");
    }

    /** Debe llamarse dentro de una transacción: lee las identidades (LAZY). */
    static UserResponse toResponse(User user) {
        List<String> providers = user.getIdentities().stream()
                .map(identity -> identity.getProvider().name())
                .distinct()
                .sorted()
                .toList();
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.hasPassword(), providers);
    }
}
