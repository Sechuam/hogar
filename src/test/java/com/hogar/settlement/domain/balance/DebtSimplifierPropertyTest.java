package com.hogar.settlement.domain.balance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Propiedades de la simplificación sobre miles de casos aleatorios reproducibles (semilla fija):
 * 2–12 personas, balances de 0,01 a 100 000,00 con ceros y empates frecuentes.
 */
@DisplayName("DebtSimplifier: propiedades con casos aleatorios")
class DebtSimplifierPropertyTest {

    private static final int ITERATIONS = 10_000;
    private static final long SEED = 20_261_114L;
    private static final long MAX_CENTS = 100_000_00L;

    @Test
    @DisplayName("≤ n−1 pagos, todos > 0, saldan todo, nadie paga y cobra, sin pares repetidos, determinista")
    void invariantsHold() {
        Random random = new Random(SEED);
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            Map<UUID, BigDecimal> balances = randomBalances(random);
            String context = "caso %d: %s".formatted(iteration, balances);

            List<Transfer> transfers = DebtSimplifier.simplify(balances);

            long nonZero = balances.values().stream().filter(b -> b.signum() != 0).count();
            assertThat((long) transfers.size()).as(context).isLessThanOrEqualTo(Math.max(0, nonZero - 1));
            transfers.forEach(t -> {
                assertThat(t.amount().signum()).as(context).isPositive();
                assertThat(t.amount().scale()).as(context).isEqualTo(2);
            });
            assertSettlesEverything(balances, transfers, context);
            assertNobodyPaysAndReceives(transfers, context);
            assertNoRepeatedPair(transfers, context);
            assertDeterministic(balances, transfers, random, context);
        }
    }

    /**
     * Balances aleatorios que suman 0: n − 1 valores al azar y el último compensa. Pocos valores
     * distintos en un tercio de los casos para forzar empates.
     */
    private static Map<UUID, BigDecimal> randomBalances(Random random) {
        int n = 2 + random.nextInt(11);
        boolean manyTies = random.nextInt(3) == 0;
        Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
        long sum = 0;
        for (int i = 0; i < n - 1; i++) {
            long cents = switch (random.nextInt(6)) {
                case 0 -> 0;
                case 1 -> random.nextBoolean() ? 1 : -1;
                default -> manyTies
                        ? (random.nextBoolean() ? 1 : -1) * 1_000L * (1 + random.nextInt(3))
                        : random.nextLong(-MAX_CENTS, MAX_CENTS + 1);
            };
            balances.put(new UUID(random.nextLong(), random.nextLong()), BigDecimal.valueOf(cents, 2));
            sum += cents;
        }
        balances.put(new UUID(random.nextLong(), random.nextLong()), BigDecimal.valueOf(-sum, 2));
        return balances;
    }

    /** Pagar suma al balance de quien paga y resta al de quien cobra: todos deben quedar a 0. */
    private static void assertSettlesEverything(Map<UUID, BigDecimal> balances, List<Transfer> transfers,
                                                String context) {
        Map<UUID, BigDecimal> after = new HashMap<>(balances);
        for (Transfer t : transfers) {
            assertThat(after).as(context).containsKeys(t.from(), t.to());
            after.merge(t.from(), t.amount(), BigDecimal::add);
            after.merge(t.to(), t.amount(), BigDecimal::subtract);
        }
        after.forEach((user, balance) -> assertThat(balance).as(context + " → " + user).isEqualByComparingTo(BigDecimal.ZERO));
    }

    private static void assertNobodyPaysAndReceives(List<Transfer> transfers, String context) {
        Set<UUID> payers = new HashSet<>();
        Set<UUID> receivers = new HashSet<>();
        transfers.forEach(t -> {
            payers.add(t.from());
            receivers.add(t.to());
        });
        payers.retainAll(receivers);
        assertThat(payers).as(context).isEmpty();
    }

    private static void assertNoRepeatedPair(List<Transfer> transfers, String context) {
        Set<List<UUID>> pairs = new HashSet<>();
        transfers.forEach(t -> assertThat(pairs.add(List.of(t.from(), t.to()))).as(context).isTrue());
    }

    private static void assertDeterministic(Map<UUID, BigDecimal> balances, List<Transfer> expected,
                                            Random random, String context) {
        List<Map.Entry<UUID, BigDecimal>> entries = new ArrayList<>(balances.entrySet());
        Collections.shuffle(entries, random);
        Map<UUID, BigDecimal> shuffled = new LinkedHashMap<>();
        entries.forEach(e -> shuffled.put(e.getKey(), e.getValue()));

        assertThat(DebtSimplifier.simplify(shuffled)).as(context).isEqualTo(expected);
    }
}
