package com.hogar.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Parte de un {@link SharedExpense} que corresponde a un usuario. Solo la crea y la sustituye su
 * gasto ({@link SharedExpense#replaceShares}); es inmutable.
 */
@Entity
@Table(name = "expense_shares")
public class ExpenseShare {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_id", nullable = false, updatable = false)
    private SharedExpense expense;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Base del reparto: ingreso del mes en PROPORTIONAL, 1 en EQUAL. */
    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal weight;

    protected ExpenseShare() {
        // JPA
    }

    ExpenseShare(SharedExpense expense, UUID userId, BigDecimal amount, BigDecimal weight) {
        this.id = UUID.randomUUID();
        this.expense = Objects.requireNonNull(expense, "expense");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.amount = amount;
        this.weight = weight;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ExpenseShare other && id != null && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ExpenseShare[id=" + id + ", userId=" + userId + ", amount=" + amount + "]";
    }
}
