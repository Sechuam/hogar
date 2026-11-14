package com.hogar.settlement.domain.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Especificación de la simplificación de deudas (contrato Fase 5, §2; D-041). */
@DisplayName("DebtSimplifier")
class DebtSimplifierTest {

    private static final UUID ANA = uuid(1);
    private static final UUID BEA = uuid(2);
    private static final UUID CARLOS = uuid(3);
    private static final UUID DANI = uuid(4);

    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n));
    }

    private static Map<UUID, BigDecimal> balances(Object... userAndAmount) {
        Map<UUID, BigDecimal> map = new LinkedHashMap<>();
        for (int i = 0; i < userAndAmount.length; i += 2) {
            map.put((UUID) userAndAmount[i], new BigDecimal((String) userAndAmount[i + 1]));
        }
        return map;
    }

    private static Transfer t(UUID from, UUID to, String amount) {
        return new Transfer(from, to, new BigDecimal(amount));
    }

    @Nested
    @DisplayName("casos básicos")
    class Basic {

        @Test
        @DisplayName("2 miembros: el deudor paga al acreedor exactamente su deuda")
        void twoMembers() {
            assertThat(DebtSimplifier.simplify(balances(ANA, "25.50", BEA, "-25.50")))
                    .containsExactly(t(BEA, ANA, "25.50"));
        }

        @Test
        @DisplayName("3 miembros: el mayor deudor paga primero al mayor acreedor")
        void threeMembers() {
            // Ana +60, Bea −20, Carlos −40
            assertThat(DebtSimplifier.simplify(balances(ANA, "60.00", BEA, "-20.00", CARLOS, "-40.00")))
                    .containsExactly(t(CARLOS, ANA, "40.00"), t(BEA, ANA, "20.00"));
        }

        @Test
        @DisplayName("4 miembros con dos acreedores y dos deudores: como mucho 3 pagos")
        void fourMembers() {
            // Ana +70, Bea +30, Carlos −50, Dani −50 → Carlos (empate, uuid menor) paga 50 a Ana;
            // queda Ana +20, así que el mayor acreedor ya es Bea: Dani paga 30 a Bea y 20 a Ana
            List<Transfer> transfers = DebtSimplifier.simplify(
                    balances(ANA, "70.00", BEA, "30.00", CARLOS, "-50.00", DANI, "-50.00"));

            assertThat(transfers).containsExactly(
                    t(CARLOS, ANA, "50.00"), t(DANI, BEA, "30.00"), t(DANI, ANA, "20.00"));
        }

        @Test
        @DisplayName("todo saldado (o mapa vacío) → ninguna transferencia")
        void allSettled() {
            assertThat(DebtSimplifier.simplify(balances(ANA, "0.00", BEA, "0", CARLOS, "0.00"))).isEmpty();
            assertThat(DebtSimplifier.simplify(Map.of())).isEmpty();
        }

        @Test
        @DisplayName("un solo acreedor con varios deudores: cada deudor le paga una vez, de mayor a menor")
        void singleCreditorManyDebtors() {
            assertThat(DebtSimplifier.simplify(
                    balances(ANA, "-10.00", BEA, "100.00", CARLOS, "-60.00", DANI, "-30.00")))
                    .containsExactly(t(CARLOS, BEA, "60.00"), t(DANI, BEA, "30.00"), t(ANA, BEA, "10.00"));
        }

        @Test
        @DisplayName("un solo deudor con varios acreedores: paga a cada uno")
        void singleDebtorManyCreditors() {
            assertThat(DebtSimplifier.simplify(balances(ANA, "3.33", BEA, "3.34", CARLOS, "-6.67")))
                    .containsExactly(t(CARLOS, BEA, "3.34"), t(CARLOS, ANA, "3.33"));
        }

        @Test
        @DisplayName("céntimos: 0,01 se transfiere tal cual, sin redondeos")
        void oneCent() {
            assertThat(DebtSimplifier.simplify(balances(ANA, "-0.01", BEA, "0.01", CARLOS, "0.00")))
                    .containsExactly(t(ANA, BEA, "0.01"));
        }

        @Test
        @DisplayName("escalas menores que 2 se aceptan y la salida siempre tiene escala 2")
        void normalizesScale() {
            List<Transfer> transfers = DebtSimplifier.simplify(balances(ANA, "5", BEA, "-5.0"));

            assertThat(transfers).containsExactly(t(BEA, ANA, "5.00"));
            assertThat(transfers.getFirst().amount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("ceros a la derecha más allá de 2 decimales no son fracciones de céntimo")
        void trailingZerosBeyondScaleTwo() {
            assertThat(DebtSimplifier.simplify(balances(ANA, "1.000", BEA, "-1.00")))
                    .containsExactly(t(BEA, ANA, "1.00"));
        }

        @Test
        @DisplayName("la lista devuelta es inmutable")
        void immutableResult() {
            List<Transfer> transfers = DebtSimplifier.simplify(balances(ANA, "1.00", BEA, "-1.00"));

            assertThatThrownBy(() -> transfers.add(t(ANA, BEA, "1.00")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("determinismo y empates")
    class Determinism {

        @Test
        @DisplayName("empate de deudores y de acreedores → gana el userId menor como texto")
        void tiesByUserIdText() {
            assertThat(DebtSimplifier.simplify(
                    balances(DANI, "10.00", CARLOS, "10.00", BEA, "-10.00", ANA, "-10.00")))
                    .containsExactly(t(ANA, CARLOS, "10.00"), t(BEA, DANI, "10.00"));
        }

        @Test
        @DisplayName("empate por texto, no por UUID.compareTo (que compara long con signo)")
        void tieBreakUsesTextNotSignedCompare() {
            UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
            UUID low = UUID.fromString("10000000-0000-0000-0000-000000000000");
            // UUID.compareTo diría high < low (bits más significativos negativos)
            assertThat(high.compareTo(low)).isNegative();

            assertThat(DebtSimplifier.simplify(balances(high, "-5.00", low, "-5.00", ANA, "10.00")))
                    .containsExactly(t(low, ANA, "5.00"), t(high, ANA, "5.00"));
        }

        @Test
        @DisplayName("distinto orden de entrada del Map → misma salida")
        void independentOfMapOrder() {
            Map<UUID, BigDecimal> forward = balances(ANA, "70.00", BEA, "30.00", CARLOS, "-50.00", DANI, "-50.00");
            Map<UUID, BigDecimal> backward = balances(DANI, "-50.00", CARLOS, "-50.00", BEA, "30.00", ANA, "70.00");
            Map<UUID, BigDecimal> hashed = new HashMap<>(forward);

            List<Transfer> expected = DebtSimplifier.simplify(forward);
            assertThat(DebtSimplifier.simplify(backward)).isEqualTo(expected);
            assertThat(DebtSimplifier.simplify(hashed)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("entradas inválidas")
    class Invalid {

        @Test
        @DisplayName("suma ≠ 0 → IllegalArgumentException")
        void sumNotZero() {
            assertThatThrownBy(() -> DebtSimplifier.simplify(balances(ANA, "10.00", BEA, "-9.99")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("0.01");
        }

        @Test
        @DisplayName("solo acreedores → la suma no es 0")
        void onlyCreditors() {
            assertThatThrownBy(() -> DebtSimplifier.simplify(balances(ANA, "10.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("escala 3 con fracción de céntimo → IllegalArgumentException")
        void scaleThree() {
            assertThatThrownBy(() -> DebtSimplifier.simplify(balances(ANA, "0.005", BEA, "-0.005")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ANA.toString());
        }

        @Test
        @DisplayName("valor nulo → IllegalArgumentException")
        void nullValue() {
            Map<UUID, BigDecimal> map = new HashMap<>();
            map.put(ANA, null);

            assertThatThrownBy(() -> DebtSimplifier.simplify(map))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ANA.toString());
        }

        @Test
        @DisplayName("clave nula → IllegalArgumentException")
        void nullKey() {
            Map<UUID, BigDecimal> map = new HashMap<>();
            map.put(null, BigDecimal.ZERO);

            assertThatThrownBy(() -> DebtSimplifier.simplify(map))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("mapa nulo → NullPointerException")
        void nullMap() {
            assertThatThrownBy(() -> DebtSimplifier.simplify(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Transfer")
    class TransferRecord {

        @Test
        @DisplayName("rechaza mismo origen y destino, importe ≤ 0 y escala ≠ 2")
        void validates() {
            BigDecimal one = new BigDecimal("1.00");
            assertThatThrownBy(() -> new Transfer(ANA, ANA, one)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Transfer(ANA, BEA, new BigDecimal("0.00")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Transfer(ANA, BEA, new BigDecimal("1")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Transfer(null, BEA, one)).isInstanceOf(NullPointerException.class);
        }
    }
}
