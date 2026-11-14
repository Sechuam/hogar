package com.hogar.settlement.domain.balance;

import com.hogar.shared.money.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Simplificación de deudas: convierte balances netos en una lista corta de pagos (contrato Fase 5, §2).
 *
 * <p>Algoritmo voraz y determinista, en céntimos enteros (sin redondeos): en cada paso el mayor
 * deudor paga al mayor acreedor {@code min(|deuda|, crédito)}. Empates de importe → {@code userId}
 * menor comparado como texto (D-041), así que el resultado no depende del orden del {@code Map}.
 *
 * <p>Garantías, con {@code n} = nº de balances ≠ 0:
 * <ul>
 *   <li>como mucho {@code n − 1} transferencias: cada paso deja a 0 al menos a una persona y el
 *       último deja a 0 a las dos;</li>
 *   <li>todas son &gt; 0 y aplicarlas deja todos los balances a 0;</li>
 *   <li>los deudores solo pagan y los acreedores solo cobran;</li>
 *   <li>no se repite ningún par: tras un pago uno de los dos queda saldado.</li>
 * </ul>
 * No garantiza el mínimo absoluto de transferencias (problema NP-difícil), solo la cota {@code n − 1}.
 */
public final class DebtSimplifier {

    /** Mayor importe primero; empate → {@code userId} menor como texto. */
    private static final Comparator<Position> LARGEST_FIRST = Comparator
            .comparing(Position::cents, Comparator.reverseOrder())
            .thenComparing(Position::key);

    private DebtSimplifier() {
    }

    /**
     * Pagos que saldan {@code balances}.
     *
     * @param balances positivo = acreedor, negativo = deudor; como mucho 2 decimales; los 0 se ignoran
     * @return transferencias en el orden en que las genera el algoritmo (determinista)
     * @throws NullPointerException     si {@code balances} es nulo
     * @throws IllegalArgumentException si una clave o un valor son nulos, algún valor tiene fracciones
     *                                  de céntimo o la suma no es exactamente 0
     */
    public static List<Transfer> simplify(Map<UUID, BigDecimal> balances) {
        Objects.requireNonNull(balances, "balances");
        PriorityQueue<Position> creditors = new PriorityQueue<>(LARGEST_FIRST);
        PriorityQueue<Position> debtors = new PriorityQueue<>(LARGEST_FIRST);
        BigInteger sum = BigInteger.ZERO;
        for (Map.Entry<UUID, BigDecimal> entry : balances.entrySet()) {
            UUID userId = entry.getKey();
            if (userId == null) {
                throw new IllegalArgumentException("userId nulo en balances");
            }
            BigInteger cents = toCents(userId, entry.getValue());
            sum = sum.add(cents);
            switch (cents.signum()) {
                case 1 -> creditors.add(new Position(userId, cents));
                case -1 -> debtors.add(new Position(userId, cents.negate()));
                default -> { }
            }
        }
        if (sum.signum() != 0) {
            throw new IllegalArgumentException("los balances deben sumar 0 y suman " + Money.fromCents(sum));
        }

        List<Transfer> transfers = new ArrayList<>();
        while (!debtors.isEmpty()) {
            // Σ = 0 garantiza que mientras haya deudores hay acreedores
            Position debtor = debtors.poll();
            Position creditor = creditors.poll();
            BigInteger paid = debtor.cents().min(creditor.cents());
            transfers.add(new Transfer(debtor.userId(), creditor.userId(), Money.fromCents(paid)));
            requeueRemainder(debtors, debtor, paid);
            requeueRemainder(creditors, creditor, paid);
        }
        if (!creditors.isEmpty()) {
            throw new IllegalStateException("invariante rota: quedan acreedores sin cobrar " + creditors);
        }
        return List.copyOf(transfers);
    }

    private static BigInteger toCents(UUID userId, BigDecimal amount) {
        try {
            return Money.toCents(amount);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("balance inválido de " + userId + ": " + e.getMessage(), e);
        }
    }

    private static void requeueRemainder(PriorityQueue<Position> queue, Position position, BigInteger paid) {
        BigInteger rest = position.cents().subtract(paid);
        if (rest.signum() > 0) {
            queue.add(new Position(position.userId(), rest));
        }
    }

    /** Importe pendiente (siempre &gt; 0) de una persona, en céntimos. */
    private record Position(UUID userId, BigInteger cents) {

        String key() {
            return userId.toString();
        }
    }
}
