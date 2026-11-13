package com.hogar.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Refresh token opaco y rotatorio. Solo se guarda su SHA-256 en hexadecimal ({@code tokenHash}).
 *
 * <p>Ciclo de vida: activo → rotado ({@link #markReplacedBy}, queda revocado y apunta a su
 * sucesor) o revocado ({@link #revoke}). Presentar un token no activo de una familia es
 * reutilización: el servicio revoca la familia entera.
 *
 * <p>El usuario se referencia por ID: es de otra feature ({@code user}).
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Transient
    private boolean isNew = true;

    protected RefreshToken() {
        // JPA
    }

    private RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId, "userId");
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be after creation time");
        }
    }

    /** Primer token de una sesión (login/registro): abre una familia nueva. */
    public static RefreshToken startFamily(UUID userId, String tokenHash, Instant now, Instant expiresAt) {
        return new RefreshToken(userId, UUID.randomUUID(), tokenHash, now, expiresAt);
    }

    /** Sucesor de {@code previous} al rotar: misma familia y mismo usuario. */
    public static RefreshToken rotateFrom(RefreshToken previous, String tokenHash, Instant now, Instant expiresAt) {
        return new RefreshToken(previous.userId, previous.familyId, tokenHash, now, expiresAt);
    }

    /** Ni revocado ni caducado ({@code expiresAt} es exclusivo). */
    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Revoca el token. Idempotente: conserva la primera fecha de revocación. */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = Objects.requireNonNull(now, "now");
        }
    }

    /** Marca el token como rotado: queda revocado y enlazado a su sucesor. */
    public void markReplacedBy(UUID newId, Instant now) {
        this.replacedById = Objects.requireNonNull(newId, "newId");
        revoke(now);
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

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedById() {
        return replacedById;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof RefreshToken other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "RefreshToken[id=" + id + ", familyId=" + familyId + "]";
    }
}
