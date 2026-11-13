package com.hogar.shared.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Utilidades mínimas para importes monetarios en {@link BigDecimal} con escala 2 (céntimos).
 *
 * <p>No es un value object: el contrato usa {@code BigDecimal} en DTOs y entidades
 * ({@code NUMERIC(19,2)}), así que aquí solo se centralizan la validación, la normalización a
 * escala 2 y la conversión exacta a/desde céntimos. Ninguna operación redondea: un importe que
 * no es un número entero de céntimos se rechaza.
 */
public final class Money {

    /** Escala de todos los importes: céntimos. */
    public static final int SCALE = 2;

    private Money() {
    }

    /**
     * Valida que {@code amount} sea un importe positivo expresable en céntimos y lo devuelve con
     * escala 2. Los ceros a la derecha no cuentan ({@code 10.000} es válido y vale {@code 10.00}).
     *
     * @throws IllegalArgumentException si es nulo, ≤ 0 o tiene fracciones de céntimo
     */
    public static BigDecimal requirePositiveAmount(BigDecimal amount) {
        BigDecimal normalized = normalize(amount, "amount");
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("amount debe ser mayor que 0: " + amount);
        }
        return normalized;
    }

    /**
     * Como {@link #requirePositiveAmount(BigDecimal)} pero admite 0 (p. ej. ingresos de un mes).
     *
     * @throws IllegalArgumentException si es nulo, negativo o tiene fracciones de céntimo
     */
    public static BigDecimal requireNonNegativeAmount(BigDecimal amount) {
        BigDecimal normalized = normalize(amount, "amount");
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException("amount no puede ser negativo: " + amount);
        }
        return normalized;
    }

    /**
     * Importe en céntimos, exacto.
     *
     * @throws IllegalArgumentException si es nulo o tiene fracciones de céntimo
     */
    public static BigInteger toCents(BigDecimal amount) {
        return normalize(amount, "amount").unscaledValue();
    }

    /** Importe con escala 2 a partir de céntimos. */
    public static BigDecimal fromCents(BigInteger cents) {
        Objects.requireNonNull(cents, "cents");
        return new BigDecimal(cents, SCALE);
    }

    /** Importe con escala 2 a partir de céntimos. */
    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, SCALE);
    }

    private static BigDecimal normalize(BigDecimal amount, String name) {
        if (amount == null) {
            throw new IllegalArgumentException(name + " es obligatorio");
        }
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException(name + " no puede tener más de " + SCALE + " decimales: " + amount);
        }
        return amount.setScale(SCALE);
    }
}
