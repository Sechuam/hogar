package com.hogar.shared.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Reparto de un importe en céntimos proporcional a unos pesos por el método del mayor resto
 * (Hamilton, D-011), con aritmética entera exacta (D-037).
 *
 * <p>Algoritmo, con {@code T} = total en céntimos, {@code w_i} = pesos como enteros y
 * {@code W = Σ w_i}:
 * <ol>
 *   <li>{@code base_i = ⌊T·w_i / W⌋} y {@code resto_i = T·w_i mod W} (sin redondeos intermedios).</li>
 *   <li>Sobran {@code T − Σ base_i} céntimos, que siempre son menos que los restos positivos.</li>
 *   <li>Se dan de uno en uno a los mayores restos; empate → el que aparece antes en la lista.</li>
 * </ol>
 * Consecuencias: {@code Σ partes == T}; cada parte difiere de su valor exacto en menos de un
 * céntimo; un peso 0 recibe exactamente 0. El orden de la lista es el criterio de desempate, así
 * que el llamante decide el orden estable (p. ej. antigüedad en el hogar).
 *
 * <p>Vive en {@code shared} porque lo usan varias features (reparto de gastos y porcentajes del
 * resumen de ingresos) y no contiene reglas de negocio de ninguna.
 */
public final class LargestRemainder {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");

    private LargestRemainder() {
    }

    /**
     * Reparte {@code amount} proporcionalmente a {@code weights}.
     *
     * @param amount  importe ≥ 0 expresable en céntimos
     * @param weights pesos ≥ 0 (cualquier escala) con suma &gt; 0; su orden es el de desempate
     * @return una parte por peso, en el mismo orden, con escala 2; suman exactamente {@code amount}
     * @throws IllegalArgumentException si algún argumento no cumple lo anterior
     */
    public static List<BigDecimal> allocate(BigDecimal amount, List<BigDecimal> weights) {
        BigInteger total = Money.toCents(Money.requireNonNegativeAmount(amount));
        List<BigInteger> parts = allocateUnits(total, toIntegerWeights(weights));
        return parts.stream().map(Money::fromCents).toList();
    }

    /**
     * Porcentajes (escala 2) de cada peso sobre el total, que suman exactamente {@code 100.00}.
     * Mismo algoritmo y desempate que {@link #allocate(BigDecimal, List)}.
     *
     * @throws IllegalArgumentException si la lista está vacía, algún peso es negativo o todos son 0
     */
    public static List<BigDecimal> percentages(List<BigDecimal> weights) {
        return allocate(ONE_HUNDRED, weights);
    }

    /**
     * Núcleo entero: reparte {@code total} unidades indivisibles según {@code weights}.
     *
     * @param total   unidades a repartir (≥ 0)
     * @param weights pesos enteros ≥ 0 con suma &gt; 0; su orden es el de desempate
     * @return una cantidad por peso, en el mismo orden; suman exactamente {@code total}
     */
    public static List<BigInteger> allocateUnits(BigInteger total, List<BigInteger> weights) {
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(weights, "weights");
        if (total.signum() < 0) {
            throw new IllegalArgumentException("total no puede ser negativo: " + total);
        }
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("hace falta al menos un peso");
        }
        BigInteger weightSum = BigInteger.ZERO;
        for (BigInteger w : weights) {
            Objects.requireNonNull(w, "weight");
            if (w.signum() < 0) {
                throw new IllegalArgumentException("los pesos no pueden ser negativos: " + w);
            }
            weightSum = weightSum.add(w);
        }
        if (weightSum.signum() == 0) {
            throw new IllegalArgumentException("la suma de los pesos debe ser mayor que 0");
        }

        int n = weights.size();
        BigInteger[] parts = new BigInteger[n];
        BigInteger[] remainders = new BigInteger[n];
        BigInteger assigned = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = total.multiply(weights.get(i)).divideAndRemainder(weightSum);
            parts[i] = qr[0];
            remainders[i] = qr[1];
            assigned = assigned.add(qr[0]);
        }

        // leftover < nº de restos > 0 (Σ resto_i = leftover·W y cada resto_i < W), así que los
        // céntimos sobrantes solo van a restos positivos: un peso 0 (resto 0) nunca recibe nada.
        int leftover = total.subtract(assigned).intValueExact();
        IntStream.range(0, n)
                .filter(i -> remainders[i].signum() > 0)
                .boxed()
                .sorted(Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .limit(leftover)
                .forEach(i -> parts[i] = parts[i].add(BigInteger.ONE));

        List<BigInteger> result = List.of(parts);
        BigInteger sum = result.stream().reduce(BigInteger.ZERO, BigInteger::add);
        if (sum.compareTo(total) != 0) {
            throw new IllegalStateException("invariante rota: Σ partes " + sum + " != total " + total);
        }
        return result;
    }

    /** Escala todos los pesos a la mayor escala presente y toma sus valores no escalados (exacto). */
    private static List<BigInteger> toIntegerWeights(List<BigDecimal> weights) {
        Objects.requireNonNull(weights, "weights");
        int scale = 0;
        for (BigDecimal w : weights) {
            Objects.requireNonNull(w, "weight");
            scale = Math.max(scale, w.scale());
        }
        List<BigInteger> result = new ArrayList<>(weights.size());
        for (BigDecimal w : weights) {
            result.add(w.setScale(scale).unscaledValue());
        }
        return result;
    }
}
