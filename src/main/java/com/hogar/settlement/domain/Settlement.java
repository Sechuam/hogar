package com.hogar.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Liquidación: "{@code fromUserId} pagó {@code amount} a {@code toUserId}" fuera de la app. La app no
 * mueve dinero: solo lo anota para los balances.
 *
 * <p>Inmutable: sin {@code @Version} ni métodos de modificación; para corregirla se borra y se
 * registra otra. Hogar y usuarios se referencian por ID (D-017); las partes pueden ser ex-miembros,
 * por eso no hay FK a las membresías.
 */
@Entity
@Immutable
@Table(name = "settlements")
public class Settlement implements Persistable<UUID> {

    public static final int MAX_NOTE_LENGTH = 200;
    /** Rango de fechas de la API (D-047); coincide con {@code ck_settlements_date_range}. */
    public static final LocalDate MIN_DATE = LocalDate.of(1, 1, 1);
    public static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);
    /** Precisión de {@code numeric(19,2)}. */
    private static final int MAX_PRECISION = 19;

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "from_user_id", nullable = false, updatable = false)
    private UUID fromUserId;

    @Column(name = "to_user_id", nullable = false, updatable = false)
    private UUID toUserId;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "settlement_date", nullable = false, updatable = false)
    private LocalDate settlementDate;

    @Column(length = MAX_NOTE_LENGTH, updatable = false)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Settlement() {
        // JPA
    }

    private Settlement(UUID householdId, UUID fromUserId, UUID toUserId, BigDecimal amount, String currency,
            LocalDate date, String noteOrNull, UUID createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.fromUserId = Objects.requireNonNull(fromUserId, "fromUserId");
        this.toUserId = Objects.requireNonNull(toUserId, "toUserId");
        if (fromUserId.equals(toUserId)) {
            throw new IllegalArgumentException("fromUserId and toUserId must be different");
        }
        this.amount = requirePositiveAmount(amount);
        this.currency = requireCurrency(currency);
        this.settlementDate = requireDate(date);
        this.note = normalizeNote(noteOrNull);
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Anota que {@code fromUserId} pagó {@code amount} a {@code toUserId}.
     *
     * @param amount     &gt; 0, como mucho 2 decimales (se normaliza a escala 2)
     * @param currency   la del hogar (ISO-4217 en mayúsculas)
     * @param date       entre {@link #MIN_DATE} y {@link #MAX_DATE}
     * @param noteOrNull opcional: se recorta; en blanco se guarda {@code null}; como mucho
     *                   {@value #MAX_NOTE_LENGTH} caracteres (puntos de código) tras recortar
     * @param createdBy  quien la registra (puede no ser ninguna de las partes, p. ej. un OWNER)
     * @throws IllegalArgumentException si algún dato no es válido o las partes coinciden
     */
    public static Settlement create(UUID householdId, UUID fromUserId, UUID toUserId, BigDecimal amount,
            String currency, LocalDate date, String noteOrNull, UUID createdBy, Instant now) {
        return new Settlement(householdId, fromUserId, toUserId, amount, currency, date, noteOrNull, createdBy,
                now);
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
        if (normalized.precision() > MAX_PRECISION) {
            throw new IllegalArgumentException("amount is too large");
        }
        return normalized;
    }

    private static String requireCurrency(String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code in upper case");
        }
        return currency;
    }

    private static LocalDate requireDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
            throw new IllegalArgumentException("date must be between " + MIN_DATE + " and " + MAX_DATE);
        }
        return date;
    }

    private static String normalizeNote(String noteOrNull) {
        if (noteOrNull == null || noteOrNull.isBlank()) {
            return null;
        }
        String stripped = noteOrNull.strip();
        // Puntos de código, como cuenta PostgreSQL en varchar (no unidades UTF-16).
        if (stripped.codePointCount(0, stripped.length()) > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note must have at most " + MAX_NOTE_LENGTH + " characters");
        }
        return stripped;
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

    /** Quien pagó. */
    public UUID getFromUserId() {
        return fromUserId;
    }

    /** Quien recibió. */
    public UUID getToUserId() {
        return toUserId;
    }

    /** Escala 2. */
    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    /** Fecha del pago ({@code date} en la API; columna {@code settlement_date}). */
    public LocalDate getDate() {
        return settlementDate;
    }

    /** Nota recortada, o {@code null} si no hay. */
    public String getNote() {
        return note;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** {@code true} si el usuario es quien paga o quien recibe. */
    public boolean involves(UUID userId) {
        return fromUserId.equals(userId) || toUserId.equals(userId);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Settlement other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Settlement[id=" + id + ", householdId=" + householdId + ", from=" + fromUserId + ", to="
                + toUserId + ", amount=" + amount + "]";
    }
}
