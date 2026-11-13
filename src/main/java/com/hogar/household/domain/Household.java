package com.hogar.household.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Hogar compartido. Agregado editable con bloqueo optimista ({@code version}). */
@Entity
@Table(name = "households")
public class Household {

    public static final SplitPolicy DEFAULT_SPLIT_POLICY = SplitPolicy.EQUAL;
    public static final String DEFAULT_CURRENCY = "EUR";
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_policy", nullable = false, length = 20)
    private SplitPolicy splitPolicy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HouseholdPlan plan;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code null} hasta el primer persist: Spring Data lo usa para distinguir alta de edición. */
    @Version
    private Long version;

    protected Household() {
        // JPA
    }

    private Household(String name, SplitPolicy splitPolicy, String currency, Instant now) {
        this.id = UUID.randomUUID();
        this.name = requireName(name);
        this.splitPolicy = splitPolicy != null ? splitPolicy : DEFAULT_SPLIT_POLICY;
        this.plan = HouseholdPlan.FREE;
        this.currency = requireCurrency(currency != null ? currency : DEFAULT_CURRENCY);
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * Crea un hogar en plan {@code FREE}.
     *
     * @param splitPolicy opcional, por defecto {@link #DEFAULT_SPLIT_POLICY}
     * @param currency    opcional (ISO-4217 en mayúsculas), por defecto {@link #DEFAULT_CURRENCY}
     */
    public static Household create(String name, SplitPolicy splitPolicy, String currency, Instant now) {
        return new Household(name, splitPolicy, currency, now);
    }

    public void rename(String newName, Instant now) {
        this.name = requireName(newName);
        touch(now);
    }

    public void changeSplitPolicy(SplitPolicy newPolicy, Instant now) {
        this.splitPolicy = Objects.requireNonNull(newPolicy, "splitPolicy");
        touch(now);
    }

    public void changePlan(HouseholdPlan newPlan, Instant now) {
        this.plan = Objects.requireNonNull(newPlan, "plan");
        touch(now);
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.strip();
    }

    private static String requireCurrency(String currency) {
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code in upper case");
        }
        return currency;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public SplitPolicy getSplitPolicy() {
        return splitPolicy;
    }

    public HouseholdPlan getPlan() {
        return plan;
    }

    public String getCurrency() {
        return currency;
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

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Household other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Household[id=" + id + "]";
    }
}
