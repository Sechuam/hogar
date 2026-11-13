package com.hogar.income.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * Ingreso de un miembro del hogar. Puntual (cuenta en el mes de {@code startDate}) o recurrente
 * (cuenta una vez cada mes desde {@code startDate} hasta el mes de {@code endDate}, o sin fin).
 *
 * <p>Hogar y usuario se referencian por ID (D-017). La moneda es la del hogar: no se guarda aquí.
 */
@Entity
@Table(name = "incomes")
public class Income {

    static final int MAX_CONCEPT_LENGTH = 200;

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = MAX_CONCEPT_LENGTH)
    private String concept;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private boolean recurring;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code null} hasta el primer persist: Spring Data lo usa para distinguir alta de edición. */
    @Version
    private Long version;

    protected Income() {
        // JPA
    }

    private Income(UUID householdId, UUID userId, Instant now) {
        this.id = UUID.randomUUID();
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Registra un ingreso.
     *
     * @param amount        &gt; 0, como mucho 2 decimales (se normaliza a escala 2)
     * @param date          fecha del puntual o primer día en que cuenta el recurrente
     * @param endDateOrNull solo en recurrentes; último día en que cuenta (incluido su mes), ≥ {@code date}
     * @throws IllegalArgumentException si algún dato no es válido
     */
    public static Income create(UUID householdId, UUID userId, BigDecimal amount, String concept, LocalDate date,
            boolean recurring, LocalDate endDateOrNull, Instant now) {
        Income income = new Income(householdId, userId, now);
        income.apply(amount, concept, date, recurring, endDateOrNull, now);
        return income;
    }

    /** Sustituye los datos editables (PUT). Mismas reglas que {@link #create}. */
    public void update(BigDecimal amount, String concept, LocalDate date, boolean recurring, LocalDate endDateOrNull,
            Instant now) {
        apply(amount, concept, date, recurring, endDateOrNull, now);
    }

    /**
     * Si el ingreso cuenta en {@code month}: puntual → mes de {@code startDate}; recurrente →
     * {@code mes(startDate) ≤ month ≤ mes(endDate)} (sin {@code endDate}, indefinido).
     */
    public boolean appliesTo(YearMonth month) {
        Objects.requireNonNull(month, "month");
        YearMonth first = YearMonth.from(startDate);
        if (!recurring) {
            return first.equals(month);
        }
        return !month.isBefore(first) && (endDate == null || !month.isAfter(YearMonth.from(endDate)));
    }

    private void apply(BigDecimal amount, String concept, LocalDate date, boolean recurring, LocalDate endDateOrNull,
            Instant now) {
        Objects.requireNonNull(date, "date");
        if (endDateOrNull != null && (!recurring || endDateOrNull.isBefore(date))) {
            throw new IllegalArgumentException("endDate is only allowed for recurring incomes and must be >= date");
        }
        this.amount = requirePositiveAmount(amount);
        this.concept = requireConcept(concept);
        this.startDate = date;
        this.recurring = recurring;
        this.endDate = endDateOrNull;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static BigDecimal requirePositiveAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("amount must have at most 2 decimals");
        }
        BigDecimal normalized = amount.setScale(2);
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException("amount is too large");
        }
        return normalized;
    }

    private static String requireConcept(String concept) {
        if (concept == null || concept.isBlank()) {
            throw new IllegalArgumentException("concept must not be blank");
        }
        String stripped = concept.strip();
        if (stripped.length() > MAX_CONCEPT_LENGTH) {
            throw new IllegalArgumentException("concept must have at most " + MAX_CONCEPT_LENGTH + " characters");
        }
        return stripped;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getConcept() {
        return concept;
    }

    /** Fecha del ingreso puntual o primer día en que cuenta el recurrente ({@code date} en la API). */
    public LocalDate getStartDate() {
        return startDate;
    }

    public boolean isRecurring() {
        return recurring;
    }

    /** {@code null} en puntuales y en recurrentes sin fin. */
    public LocalDate getEndDate() {
        return endDate;
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
        return this == o || (o instanceof Income other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Income[id=" + id + ", householdId=" + householdId + ", userId=" + userId + "]";
    }
}
