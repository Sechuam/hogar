package com.hogar.user.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Usuario de la aplicación. Raíz del agregado que incluye sus {@link UserIdentity identidades}
 * externas.
 *
 * <p>El email se guarda siempre normalizado ({@link #normalizeEmail(String)}); la BD lo refuerza
 * con un {@code CHECK}. Un usuario sin {@code passwordHash} solo puede entrar con un proveedor
 * externo.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code null} hasta el primer persist: Spring Data lo usa para distinguir alta de edición. */
    @Version
    private Long version;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserIdentity> identities = new ArrayList<>();

    protected User() {
        // JPA
    }

    private User(String email, String displayName, String passwordHash, boolean emailVerified, Instant now) {
        this.id = UUID.randomUUID();
        this.email = normalizeEmail(email);
        this.displayName = requireText(displayName, "displayName");
        this.passwordHash = passwordHash;
        this.emailVerified = emailVerified;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /** Alta con email y contraseña. El email queda sin verificar. */
    public static User registerWithPassword(String email, String displayName, String passwordHash, Instant now) {
        return new User(email, displayName, requireText(passwordHash, "passwordHash"), false, now);
    }

    /**
     * Alta desde un proveedor externo que ya verificó el email (p. ej. Google). Sin contraseña.
     * La identidad se vincula después con {@link #linkIdentity}.
     */
    public static User registerExternal(String email, String displayName, Instant now) {
        return new User(email, displayName, null, true, now);
    }

    /** Forma canónica de un email: sin espacios alrededor y en minúsculas ({@link Locale#ROOT}). */
    public static String normalizeEmail(String email) {
        return requireText(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    /** Vincula una identidad externa a este usuario y la devuelve (se persiste en cascada). */
    public UserIdentity linkIdentity(IdentityProvider provider, String subject, String providerEmail, Instant now) {
        UserIdentity identity = new UserIdentity(this, provider, subject, providerEmail, now);
        identities.add(identity);
        touch(now);
        return identity;
    }

    public void markEmailVerified(Instant now) {
        if (!emailVerified) {
            emailVerified = true;
            touch(now);
        }
    }

    public void changeDisplayName(String newDisplayName, Instant now) {
        this.displayName = requireText(newDisplayName, "displayName");
        touch(now);
    }

    public void changePasswordHash(String newPasswordHash, Instant now) {
        this.passwordHash = requireText(newPasswordHash, "passwordHash");
        touch(now);
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    /** Vista de solo lectura. Acceder fuera de una transacción lanza {@code LazyInitializationException}. */
    public List<UserIdentity> getIdentities() {
        return Collections.unmodifiableList(identities);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof User other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "User[id=" + id + "]";
    }
}
