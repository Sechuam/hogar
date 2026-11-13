package com.hogar.household.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Pertenencia de un usuario a un hogar. No se borra nunca: al abandonar o ser expulsado se
 * rellena {@code leftAt}; volver a unirse crea una membresía nueva. La BD garantiza como mucho
 * una activa por usuario y hogar.
 *
 * <p>Hogar y usuario se referencian por ID (agregados distintos; el usuario es de otra feature).
 */
@Entity
@Table(name = "memberships")
public class Membership implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HouseholdRole role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Transient
    private boolean isNew = true;

    protected Membership() {
        // JPA
    }

    private Membership(UUID householdId, UUID userId, HouseholdRole role, Instant now) {
        this.id = UUID.randomUUID();
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.role = Objects.requireNonNull(role, "role");
        this.joinedAt = Objects.requireNonNull(now, "now");
    }

    public static Membership join(UUID householdId, UUID userId, HouseholdRole role, Instant now) {
        return new Membership(householdId, userId, role, now);
    }

    /** Membresía del creador del hogar. */
    public static Membership owner(UUID householdId, UUID userId, Instant now) {
        return join(householdId, userId, HouseholdRole.OWNER, now);
    }

    /** Membresía de quien entra con una invitación. */
    public static Membership member(UUID householdId, UUID userId, Instant now) {
        return join(householdId, userId, HouseholdRole.MEMBER, now);
    }

    public boolean isActive() {
        return leftAt == null;
    }

    public boolean isOwner() {
        return role == HouseholdRole.OWNER;
    }

    /**
     * Cierra la membresía (abandono o expulsión).
     *
     * @throws IllegalStateException si ya estaba cerrada
     */
    public void leave(Instant now) {
        if (leftAt != null) {
            throw new IllegalStateException("Membership " + id + " is no longer active");
        }
        this.leftAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Cambia el rol. La regla "no dejar el hogar sin OWNER" involucra a otras membresías y la
     * comprueba el servicio.
     *
     * @throws IllegalStateException si la membresía ya no está activa
     */
    public void changeRole(HouseholdRole newRole) {
        if (leftAt != null) {
            throw new IllegalStateException("Membership " + id + " is no longer active");
        }
        this.role = Objects.requireNonNull(newRole, "role");
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

    public UUID getUserId() {
        return userId;
    }

    public HouseholdRole getRole() {
        return role;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getLeftAt() {
        return leftAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Membership other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Membership[id=" + id + ", householdId=" + householdId + ", userId=" + userId + ", role=" + role + "]";
    }
}
