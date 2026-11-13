package com.hogar.household.domain;

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
import org.springframework.data.domain.Persistable;

/**
 * Invitación a un hogar con código compartible. Caduca en {@code expiresAt} (exclusivo), puede
 * limitarse a {@code maxUses} usos ({@code null} = ilimitada) y el OWNER puede revocarla.
 *
 * <p>Generar el código (aleatorio, no adivinable) es responsabilidad del servicio.
 */
@Entity
@Table(name = "invitations")
public class Invitation implements Persistable<UUID> {

    public static final int MAX_CODE_LENGTH = 16;

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(nullable = false, length = MAX_CODE_LENGTH, updatable = false)
    private String code;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "max_uses", updatable = false)
    private Integer maxUses;

    @Column(nullable = false)
    private int uses;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Transient
    private boolean isNew = true;

    protected Invitation() {
        // JPA
    }

    private Invitation(UUID householdId, String code, UUID createdBy, Instant now, Instant expiresAt, Integer maxUses) {
        this.id = UUID.randomUUID();
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.code = requireCode(code);
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be after creation time");
        }
        if (maxUses != null && maxUses < 1) {
            throw new IllegalArgumentException("maxUses must be at least 1");
        }
        this.maxUses = maxUses;
        this.uses = 0;
    }

    /** @param maxUses {@code null} = usos ilimitados hasta caducar */
    public static Invitation create(
            UUID householdId, String code, UUID createdBy, Instant now, Instant expiresAt, Integer maxUses) {
        return new Invitation(householdId, code, createdBy, now, expiresAt, maxUses);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isExhausted() {
        return maxUses != null && uses >= maxUses;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Cuenta un uso. El servicio debe comprobar antes caducidad y revocación (con su error).
     *
     * @throws IllegalStateException si ya no quedan usos
     */
    public void registerUse() {
        if (isExhausted()) {
            throw new IllegalStateException("Invitation " + id + " has no uses left");
        }
        uses++;
    }

    /** Revoca la invitación. Idempotente: conserva la primera fecha de revocación. */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = Objects.requireNonNull(now, "now");
        }
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException("code must have between 1 and " + MAX_CODE_LENGTH + " characters");
        }
        return code;
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

    public UUID getHouseholdId() {
        return householdId;
    }

    public String getCode() {
        return code;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public int getUses() {
        return uses;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Invitation other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Invitation[id=" + id + ", householdId=" + householdId + "]";
    }
}
