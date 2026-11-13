package com.hogar.expense.domain;

import com.hogar.expense.domain.split.ShareAllocation;
import com.hogar.expense.domain.split.SplitResult;
import com.hogar.expense.domain.split.SplitWarning;
import com.hogar.household.domain.SplitPolicy;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Gasto común de un hogar con su reparto congelado (snapshot). Raíz del agregado que incluye sus
 * {@link ExpenseShare partes}: cambiar la política del hogar o los ingresos no lo modifica; solo
 * {@link #replaceShares} sustituye el reparto completo.
 *
 * <p>Hogar, pagador y creador se referencian por ID (D-017). Las partes se exponen ordenadas por
 * {@code userId} (mismo orden que {@code uuid} en PostgreSQL), igual recién creadas que leídas de BD.
 */
@Entity
@Table(name = "shared_expenses")
public class SharedExpense {

    static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** Orden de las partes; el texto en minúsculas ordena igual que {@code uuid} en PostgreSQL (bytes sin signo). */
    private static final Comparator<ExpenseShare> SHARE_ORDER = Comparator.comparing(s -> s.getUserId().toString());

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "paid_by", nullable = false)
    private UUID paidBy;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, length = MAX_DESCRIPTION_LENGTH)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExpenseCategory category;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_policy_applied", nullable = false, length = 20)
    private SplitPolicy splitPolicyApplied;

    @Column(name = "split_fallback", nullable = false)
    private boolean splitFallback;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code null} hasta el primer persist: Spring Data lo usa para distinguir alta de edición. */
    @Version
    private Long version;

    /**
     * {@code @BatchSize}: al recorrer una página de gastos, las partes se cargan en bloque
     * ({@code where expense_id in (...)}) en vez de una consulta por gasto, sin paginar en memoria.
     */
    @OneToMany(mappedBy = "expense", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("userId asc")
    @BatchSize(size = 100)
    private List<ExpenseShare> shares = new ArrayList<>();

    protected SharedExpense() {
        // JPA
    }

    private SharedExpense(UUID householdId, UUID createdBy, String currency, Instant now) {
        this.id = UUID.randomUUID();
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.currency = requireCurrency(currency);
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Registra un gasto con su reparto.
     *
     * @param amount   &gt; 0, como mucho 2 decimales (se normaliza a escala 2)
     * @param currency la del hogar (ISO-4217 en mayúsculas); no cambia después
     * @param split    reparto de {@code amount}: la suma de sus partes debe ser exactamente {@code amount}
     * @throws IllegalArgumentException si algún dato no es válido
     */
    public static SharedExpense create(UUID householdId, UUID paidBy, UUID createdBy, BigDecimal amount,
            String currency, String description, ExpenseCategory category, LocalDate date, SplitResult split,
            Instant now) {
        SharedExpense expense = new SharedExpense(householdId, createdBy, currency, now);
        expense.changeDetails(description, category, now);
        expense.changeAmountDatePayer(amount, date, paidBy, now);
        expense.replaceShares(split, now);
        return expense;
    }

    /**
     * Sustituye el reparto completo (las partes anteriores se borran por {@code orphanRemoval}).
     * Guarda también la política aplicada y si hubo fallback a partes iguales.
     *
     * @throws IllegalArgumentException si no hay partes, se repite un usuario, algún importe o peso
     *                                  no es válido, {@code Σ partes != amount}, o hay fallback con
     *                                  una política distinta de EQUAL
     */
    public void replaceShares(SplitResult split, Instant now) {
        Objects.requireNonNull(split, "split");
        SplitPolicy applied = Objects.requireNonNull(split.appliedPolicy(), "appliedPolicy");
        boolean fallback = split.warnings().contains(SplitWarning.PROPORTIONAL_FALLBACK_NO_INCOME);
        if (fallback && applied != SplitPolicy.EQUAL) {
            throw new IllegalArgumentException("a proportional fallback must be applied as EQUAL");
        }
        if (split.shares().isEmpty()) {
            throw new IllegalArgumentException("an expense needs at least one share");
        }
        List<ExpenseShare> replacement = new ArrayList<>(split.shares().size());
        Set<UUID> users = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (ShareAllocation allocation : split.shares()) {
            if (!users.add(Objects.requireNonNull(allocation.userId(), "userId"))) {
                throw new IllegalArgumentException("duplicated share for user " + allocation.userId());
            }
            BigDecimal shareAmount = requireNonNegative(allocation.amount(), "share amount");
            replacement.add(new ExpenseShare(this, allocation.userId(), shareAmount,
                    requireNonNegative(allocation.weight(), "share weight")));
            sum = sum.add(shareAmount);
        }
        if (sum.compareTo(amount) != 0) {
            throw new IllegalArgumentException("shares add up to " + sum + " but the expense amount is " + amount);
        }
        replacement.sort(SHARE_ORDER);
        // Se modifica la colección gestionada (no se reasigna) para que orphanRemoval funcione.
        shares.clear();
        shares.addAll(replacement);
        this.splitPolicyApplied = applied;
        this.splitFallback = fallback;
        touch(now);
    }

    /** Cambia lo que no afecta al reparto: las partes no se tocan. */
    public void changeDetails(String description, ExpenseCategory category, Instant now) {
        this.description = requireDescription(description);
        this.category = Objects.requireNonNull(category, "category");
        touch(now);
    }

    /**
     * Cambia lo que obliga a recalcular el reparto. No toca las partes: después hay que llamar a
     * {@link #replaceShares} con el nuevo reparto (hasta entonces pueden no sumar {@code amount}).
     */
    public void changeAmountDatePayer(BigDecimal amount, LocalDate date, UUID paidBy, Instant now) {
        this.amount = requirePositiveAmount(amount);
        this.expenseDate = Objects.requireNonNull(date, "date");
        this.paidBy = Objects.requireNonNull(paidBy, "paidBy");
        touch(now);
    }

    /** El hogar era PROPORTIONAL pero nadie tenía ingresos ese mes: se repartió a partes iguales. */
    public boolean isSplitFallback() {
        return splitFallback;
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static BigDecimal requirePositiveAmount(BigDecimal amount) {
        BigDecimal normalized = requireNonNegative(amount, "amount");
        if (normalized.signum() == 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return normalized;
    }

    /** {@code ≥ 0}, como mucho 2 decimales, cabe en {@code numeric(19,2)}; devuelve escala 2. */
    private static BigDecimal requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(name + " must have at most 2 decimals");
        }
        BigDecimal normalized = value.setScale(2);
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException(name + " is too large");
        }
        return normalized;
    }

    private static String requireDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        String stripped = description.strip();
        if (stripped.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "description must have at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return stripped;
    }

    private static String requireCurrency(String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code in upper case");
        }
        return currency;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getPaidBy() {
        return paidBy;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public ExpenseCategory getCategory() {
        return category;
    }

    /** Fecha del gasto ({@code date} en la API). */
    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    public SplitPolicy getSplitPolicyApplied() {
        return splitPolicyApplied;
    }

    /** Partes ordenadas por {@code userId}; vista no modificable. */
    public List<ExpenseShare> getShares() {
        return Collections.unmodifiableList(shares);
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
        return this == o || (o instanceof SharedExpense other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "SharedExpense[id=" + id + ", householdId=" + householdId + ", amount=" + amount + "]";
    }
}
