package com.hogar.settlement.domain.balance;

import com.hogar.expense.dto.ExpenseTotals;
import com.hogar.shared.money.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Balances netos de un hogar a partir de los totales de gastos y de liquidaciones (contrato Fase 5, §2).
 *
 * <p>Clase pura: sin Spring, sin BD. Por usuario, {@code balance = paid − owed + sent − received}
 * en céntimos exactos. Como cada gasto reparte exactamente su importe (Σ paid == Σ owed) y cada
 * liquidación suma lo mismo a quien envía que a quien recibe, {@code Σ balance == 0} siempre; si no
 * se cumple es un bug (datos incoherentes), no un error del cliente → {@link IllegalStateException}.
 */
public final class BalanceCalculator {

    private static final Comparator<UUID> USER_ORDER = Comparator.comparing(UUID::toString);

    private BalanceCalculator() {
    }

    /**
     * Calcula los balances.
     *
     * @param alwaysInclude usuarios que aparecen aunque no tengan movimientos (p. ej. miembros activos)
     * @param expenseTotals pagado/debido por usuario; ausente = 0
     * @param sent          total de liquidaciones enviadas por usuario; ausente = 0
     * @param received      total de liquidaciones recibidas por usuario; ausente = 0
     * @return un balance por usuario presente en cualquiera de las entradas, ordenados por
     *         {@code userId} como texto (D-041); el llamante aplica su propio orden de presentación
     * @throws NullPointerException     si algún argumento es nulo
     * @throws IllegalArgumentException si alguna clave o importe es nulo, o algún importe es negativo
     *                                  o tiene fracciones de céntimo
     * @throws IllegalStateException    si la suma de los balances no es exactamente 0
     */
    public static Map<UUID, UserBalance> calculate(Collection<UUID> alwaysInclude,
                                                   Map<UUID, ExpenseTotals> expenseTotals,
                                                   Map<UUID, BigDecimal> sent,
                                                   Map<UUID, BigDecimal> received) {
        Objects.requireNonNull(alwaysInclude, "alwaysInclude");
        Objects.requireNonNull(expenseTotals, "expenseTotals");
        Objects.requireNonNull(sent, "sent");
        Objects.requireNonNull(received, "received");

        TreeSet<UUID> users = new TreeSet<>(USER_ORDER);
        addAll(users, alwaysInclude);
        addAll(users, expenseTotals.keySet());
        addAll(users, sent.keySet());
        addAll(users, received.keySet());

        Map<UUID, UserBalance> result = new LinkedHashMap<>();
        BigInteger sum = BigInteger.ZERO;
        for (UUID userId : users) {
            ExpenseTotals totals = expenseTotals.get(userId);
            if (expenseTotals.containsKey(userId) && totals == null) {
                throw new IllegalArgumentException("totales de gastos nulos para " + userId);
            }
            BigInteger paid = cents(userId, "paid", totals == null ? BigDecimal.ZERO : totals.paid());
            BigInteger owed = cents(userId, "owed", totals == null ? BigDecimal.ZERO : totals.owed());
            BigInteger sentCents = cents(userId, "sent", valueOrZero(sent, userId));
            BigInteger receivedCents = cents(userId, "received", valueOrZero(received, userId));
            BigInteger balance = paid.subtract(owed).add(sentCents).subtract(receivedCents);
            sum = sum.add(balance);
            result.put(userId, new UserBalance(userId, Money.fromCents(paid), Money.fromCents(owed),
                    Money.fromCents(sentCents), Money.fromCents(receivedCents), Money.fromCents(balance)));
        }
        if (sum.signum() != 0) {
            throw new IllegalStateException("invariante rota: Σ balances = " + Money.fromCents(sum) + " != 0");
        }
        return Collections.unmodifiableMap(result);
    }

    /** Solo el balance neto de cada usuario, listo para {@link DebtSimplifier#simplify(Map)}. */
    public static Map<UUID, BigDecimal> netBalances(Map<UUID, UserBalance> balances) {
        Objects.requireNonNull(balances, "balances");
        Map<UUID, BigDecimal> net = new LinkedHashMap<>();
        balances.values().forEach(b -> net.put(b.userId(), b.balance()));
        return Collections.unmodifiableMap(net);
    }

    private static void addAll(TreeSet<UUID> users, Collection<UUID> ids) {
        for (UUID id : ids) {
            if (id == null) {
                throw new IllegalArgumentException("userId nulo");
            }
            users.add(id);
        }
    }

    private static BigDecimal valueOrZero(Map<UUID, BigDecimal> map, UUID userId) {
        if (!map.containsKey(userId)) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = map.get(userId);
        if (value == null) {
            throw new IllegalArgumentException("importe nulo para " + userId);
        }
        return value;
    }

    private static BigInteger cents(UUID userId, String field, BigDecimal amount) {
        try {
            return Money.toCents(Money.requireNonNegativeAmount(amount));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " inválido de " + userId + ": " + e.getMessage(), e);
        }
    }
}
