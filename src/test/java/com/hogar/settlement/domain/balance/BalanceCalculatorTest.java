package com.hogar.settlement.domain.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.expense.dto.ExpenseTotals;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Especificación del cálculo de balances (contrato Fase 5, §2). */
@DisplayName("BalanceCalculator")
class BalanceCalculatorTest {

    private static final UUID ANA = uuid(1);
    private static final UUID BEA = uuid(2);
    private static final UUID CARLOS = uuid(3);

    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n));
    }

    private static ExpenseTotals totals(String paid, String owed) {
        return new ExpenseTotals(new BigDecimal(paid), new BigDecimal(owed));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal sum(Map<UUID, UserBalance> balances) {
        return balances.values().stream().map(UserBalance::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Nested
    @DisplayName("cálculo")
    class Calculation {

        @Test
        @DisplayName("balance = paid − owed + sent − received, escala 2 y Σ == 0")
        void combinesExpensesAndSettlements() {
            // Ana pagó 90 (30 cada uno); Bea ya le devolvió 10
            Map<UUID, UserBalance> result = BalanceCalculator.calculate(Set.of(),
                    Map.of(ANA, totals("90.00", "30.00"), BEA, totals("0.00", "30.00"), CARLOS, totals("0", "30")),
                    Map.of(BEA, money("10.00")),
                    Map.of(ANA, money("10.00")));

            assertThat(result.get(ANA)).isEqualTo(new UserBalance(ANA, money("90.00"), money("30.00"),
                    money("0.00"), money("10.00"), money("50.00")));
            assertThat(result.get(BEA).balance()).isEqualTo(money("-20.00"));
            assertThat(result.get(CARLOS)).isEqualTo(new UserBalance(CARLOS, money("0.00"), money("30.00"),
                    money("0.00"), money("0.00"), money("-30.00")));
            assertThat(sum(result)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("importes impares: 10,00 entre 3 con céntimo al primero")
        void oddCents() {
            Map<UUID, UserBalance> result = BalanceCalculator.calculate(Set.of(),
                    Map.of(ANA, totals("10.00", "3.34"), BEA, totals("0.00", "3.33"), CARLOS, totals("0.00", "3.33")),
                    Map.of(), Map.of());

            assertThat(result.values()).extracting(UserBalance::balance)
                    .containsExactly(money("6.66"), money("-3.33"), money("-3.33"));
        }

        @Test
        @DisplayName("una liquidación de más cambia el signo del balance (se anota la realidad)")
        void overpaymentFlipsSign() {
            Map<UUID, UserBalance> result = BalanceCalculator.calculate(Set.of(),
                    Map.of(ANA, totals("20.00", "10.00"), BEA, totals("0.00", "10.00")),
                    Map.of(BEA, money("15.00")), Map.of(ANA, money("15.00")));

            assertThat(result.get(ANA).balance()).isEqualTo(money("-5.00"));
            assertThat(result.get(BEA).balance()).isEqualTo(money("5.00"));
        }

        @Test
        @DisplayName("miembros sin movimientos aparecen con todo a 0,00 si se piden")
        void alwaysIncludedUsersWithoutMovements() {
            Map<UUID, UserBalance> result = BalanceCalculator.calculate(List.of(CARLOS), Map.of(), Map.of(), Map.of());

            UserBalance carlos = result.get(CARLOS);
            assertThat(carlos).isEqualTo(new UserBalance(CARLOS, money("0.00"), money("0.00"),
                    money("0.00"), money("0.00"), money("0.00")));
            assertThat(carlos.hasMovements()).isFalse();
            assertThat(carlos.isSettled()).isTrue();
        }

        @Test
        @DisplayName("todo saldado: tiene movimientos pero balance 0")
        void settledWithMovements() {
            Map<UUID, UserBalance> result = BalanceCalculator.calculate(Set.of(),
                    Map.of(ANA, totals("10.00", "5.00"), BEA, totals("0.00", "5.00")),
                    Map.of(BEA, money("5.00")), Map.of(ANA, money("5.00")));

            assertThat(result.values()).allSatisfy(b -> {
                assertThat(b.isSettled()).isTrue();
                assertThat(b.hasMovements()).isTrue();
            });
        }

        @Test
        @DisplayName("salida ordenada por userId como texto, sea cual sea la entrada")
        void orderedByUserIdText() {
            UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
            Map<UUID, UserBalance> result = BalanceCalculator.calculate(List.of(high, CARLOS, ANA),
                    Map.of(), Map.of(), Map.of());

            assertThat(result.keySet()).containsExactly(ANA, CARLOS, high);
        }

        @Test
        @DisplayName("netBalances alimenta DebtSimplifier y salda las cuentas")
        void netBalancesFeedSimplifier() {
            Map<UUID, UserBalance> result = BalanceCalculator.calculate(Set.of(),
                    Map.of(ANA, totals("90.00", "30.00"), BEA, totals("0.00", "30.00"), CARLOS, totals("0.00", "30.00")),
                    Map.of(), Map.of());

            Map<UUID, BigDecimal> net = BalanceCalculator.netBalances(result);

            assertThat(net).containsExactly(Map.entry(ANA, money("60.00")), Map.entry(BEA, money("-30.00")),
                    Map.entry(CARLOS, money("-30.00")));
            assertThat(DebtSimplifier.simplify(net)).containsExactly(
                    new Transfer(BEA, ANA, money("30.00")), new Transfer(CARLOS, ANA, money("30.00")));
        }

        @Test
        @DisplayName("muchos gastos aleatorios repartidos al céntimo → Σ balances == 0 siempre")
        void randomSumsAreZero() {
            Random random = new Random(20_261_114L);
            List<UUID> users = List.of(ANA, BEA, CARLOS);
            for (int iteration = 0; iteration < 2_000; iteration++) {
                Map<UUID, long[]> acc = new HashMap<>();
                users.forEach(u -> acc.put(u, new long[4]));
                for (int e = 0; e < 5; e++) {
                    long cents = random.nextLong(1, 1_000_000);
                    acc.get(users.get(random.nextInt(3)))[0] += cents;
                    long rest = cents;
                    for (int i = 0; i < 2; i++) {
                        long part = random.nextLong(0, rest + 1);
                        acc.get(users.get(i))[1] += part;
                        rest -= part;
                    }
                    acc.get(users.get(2))[1] += rest;
                    long settled = random.nextLong(1, 10_000);
                    int from = random.nextInt(3);
                    acc.get(users.get(from))[2] += settled;
                    acc.get(users.get((from + 1 + random.nextInt(2)) % 3))[3] += settled;
                }
                Map<UUID, ExpenseTotals> expenseTotals = new HashMap<>();
                Map<UUID, BigDecimal> sent = new HashMap<>();
                Map<UUID, BigDecimal> received = new HashMap<>();
                acc.forEach((u, v) -> {
                    expenseTotals.put(u, new ExpenseTotals(BigDecimal.valueOf(v[0], 2), BigDecimal.valueOf(v[1], 2)));
                    sent.put(u, BigDecimal.valueOf(v[2], 2));
                    received.put(u, BigDecimal.valueOf(v[3], 2));
                });

                Map<UUID, UserBalance> result = BalanceCalculator.calculate(users, expenseTotals, sent, received);

                assertThat(sum(result)).as("caso %d", iteration).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(result.values()).allSatisfy(b -> assertThat(b.balance().scale()).isEqualTo(2));
            }
        }
    }

    @Nested
    @DisplayName("entradas inválidas")
    class Invalid {

        @Test
        @DisplayName("Σ ≠ 0 (datos incoherentes) → IllegalStateException, es un bug")
        void sumNotZero() {
            assertThatThrownBy(() -> BalanceCalculator.calculate(Set.of(),
                    Map.of(ANA, totals("10.00", "5.00"), BEA, totals("0.00", "4.99")), Map.of(), Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("0.01");
        }

        @Test
        @DisplayName("liquidación enviada sin recibida → IllegalStateException")
        void settlementWithoutCounterpart() {
            assertThatThrownBy(() -> BalanceCalculator.calculate(Set.of(), Map.of(),
                    Map.of(ANA, money("5.00")), Map.of()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("escala 3 → IllegalArgumentException")
        void scaleThree() {
            assertThatThrownBy(() -> BalanceCalculator.calculate(Set.of(), Map.of(),
                    Map.of(ANA, money("0.005")), Map.of(BEA, money("0.005"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sent");
        }

        @Test
        @DisplayName("importe negativo → IllegalArgumentException")
        void negative() {
            assertThatThrownBy(() -> BalanceCalculator.calculate(Set.of(),
                    Map.of(ANA, totals("-1.00", "0.00")), Map.of(), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("paid");
        }

        @Test
        @DisplayName("nulos → IllegalArgumentException (valores) o NullPointerException (argumentos)")
        void nulls() {
            Map<UUID, ExpenseTotals> nullTotals = new HashMap<>();
            nullTotals.put(ANA, null);
            Map<UUID, BigDecimal> nullAmount = new HashMap<>();
            nullAmount.put(ANA, null);
            Map<UUID, BigDecimal> nullKey = new HashMap<>();
            nullKey.put(null, money("1.00"));

            assertThatThrownBy(() -> BalanceCalculator.calculate(Set.of(), nullTotals, Map.of(), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> BalanceCalculator.calculate(Set.of(),
                    Map.of(ANA, new ExpenseTotals(null, money("0.00"))), Map.of(), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> BalanceCalculator.calculate(Set.of(), Map.of(), nullAmount, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> BalanceCalculator.calculate(Set.of(), Map.of(), Map.of(), nullKey))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> BalanceCalculator.calculate(null, Map.of(), Map.of(), Map.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> BalanceCalculator.netBalances(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
