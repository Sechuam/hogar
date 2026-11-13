package com.hogar.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Identidad de un usuario en un proveedor externo ({@code provider} + {@code subject} únicos).
 * Inmutable; se crea con {@link User#linkIdentity}.
 */
@Entity
@Table(name = "user_identities")
public class UserIdentity implements Persistable<UUID> {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private IdentityProvider provider;

    @Column(nullable = false, length = 255, updatable = false)
    private String subject;

    @Column(nullable = false, length = 254, updatable = false)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected UserIdentity() {
        // JPA
    }

    UserIdentity(User user, IdentityProvider provider, String subject, String email, Instant now) {
        this.id = UUID.randomUUID();
        this.user = Objects.requireNonNull(user, "user");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.email = User.normalizeEmail(email);
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof UserIdentity other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "UserIdentity[id=" + id + ", provider=" + provider + "]";
    }
}
