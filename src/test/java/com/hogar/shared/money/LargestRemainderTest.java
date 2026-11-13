package com.hogar.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LargestRemainder: reparto exacto por mayor resto")
class LargestRemainderTest {

    private static List<BigDecimal> amounts(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Nested
    @DisplayName("allocate")
    class Allocate {

        @Test
        @DisplayName("10,00 entre tres pesos iguales → 3,34 / 3,33 / 3,33 (el céntimo al primero)")
        void tenBetweenThree() {
            assertThat(LargestRemainder.allocate(new BigDecimal("10.00"), amounts("1", "1", "1")))
                    .containsExactlyElementsOf(amounts("3.34", "3.33", "3.33"));
        }

        @Test
        @DisplayName("el céntimo va al mayor resto aunque no sea el primero de la lista")
        void largestRemainderWinsOverPosition() {
            // 1,00 · (1, 2) / 3 = 0,333.. / 0,666.. → el segundo tiene mayor resto
            assertThat(LargestRemainder.allocate(new BigDecimal("1.00"), amounts("1", "2")))
                    .containsExactlyElementsOf(amounts("0.33", "0.67"));
        }

        @Test
        @DisplayName("pesos con distinta escala se comparan de forma exacta")
        void mixedScales() {
            assertThat(LargestRemainder.allocate(new BigDecimal("100"), amounts("0.5", "1.50", "2")))
                    .containsExactlyElementsOf(amounts("12.50", "37.50", "50.00"));
        }

        @Test
        @DisplayName("un peso 0 recibe 0,00 y nunca un céntimo sobrante")
        void zeroWeightGetsNothing() {
            assertThat(LargestRemainder.allocate(new BigDecimal("0.01"), amounts("0", "1", "1")))
                    .containsExactlyElementsOf(amounts("0.00", "0.01", "0.00"));
        }

        @Test
        @DisplayName("repartir 0 da todo ceros")
        void zeroAmount() {
            assertThat(LargestRemainder.allocate(BigDecimal.ZERO, amounts("1", "3")))
                    .containsExactlyElementsOf(amounts("0.00", "0.00"));
        }

        @Test
        @DisplayName("rechaza importes negativos o con fracciones de céntimo")
        void rejectsInvalidAmount() {
            assertThatThrownBy(() -> LargestRemainder.allocate(new BigDecimal("-1"), amounts("1")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LargestRemainder.allocate(new BigDecimal("1.001"), amounts("1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rechaza lista vacía, pesos negativos y suma de pesos 0")
        void rejectsInvalidWeights() {
            BigDecimal ten = BigDecimal.TEN;
            assertThatThrownBy(() -> LargestRemainder.allocate(ten, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LargestRemainder.allocate(ten, amounts("1", "-1")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LargestRemainder.allocate(ten, amounts("0", "0.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("allocateUnits")
    class AllocateUnits {

        @Test
        @DisplayName("rechaza total negativo")
        void rejectsNegativeTotal() {
            assertThatThrownBy(() -> LargestRemainder.allocateUnits(BigInteger.valueOf(-1), List.of(BigInteger.ONE)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("restos iguales: desempata el orden de la lista")
        void tieBrokenByListOrder() {
            List<BigInteger> weights = List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE);
            assertThat(LargestRemainder.allocateUnits(BigInteger.valueOf(6), weights))
                    .containsExactly(BigInteger.TWO, BigInteger.TWO, BigInteger.ONE, BigInteger.ONE);
        }
    }

    @Nested
    @DisplayName("percentages")
    class Percentages {

        @Test
        @DisplayName("1500 / 1000 → 60,00 % / 40,00 %")
        void sixtyForty() {
            assertThat(LargestRemainder.percentages(amounts("1500.00", "1000.00")))
                    .containsExactlyElementsOf(amounts("60.00", "40.00"));
        }

        @Test
        @DisplayName("tres ingresos iguales → 33,34 / 33,33 / 33,33")
        void thirds() {
            assertThat(LargestRemainder.percentages(amounts("1000", "1000", "1000")))
                    .containsExactlyElementsOf(amounts("33.34", "33.33", "33.33"));
        }

        @Test
        @DisplayName("quien no ingresa tiene 0,00 %")
        void zeroIncome() {
            assertThat(LargestRemainder.percentages(amounts("0", "2000.00", "1000.00")))
                    .containsExactlyElementsOf(amounts("0.00", "66.67", "33.33"));
        }

        @Test
        @DisplayName("todos los ingresos a 0 → IllegalArgumentException (el llamante decide qué mostrar)")
        void allZero() {
            assertThatThrownBy(() -> LargestRemainder.percentages(amounts("0", "0")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("propiedad: suman 100,00, cada uno a < 0,01 de su exacto y peso 0 → 0,00")
        void propertyHolds() {
            Random random = new Random(20_261_113L);
            BigDecimal hundred = new BigDecimal("100.00");
            for (int iteration = 0; iteration < 5_000; iteration++) {
                int n = 1 + random.nextInt(8);
                List<BigDecimal> weights = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    weights.add(random.nextInt(4) == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(random.nextLong(1, 1_000_000_00L), 2));
                }
                if (weights.stream().allMatch(w -> w.signum() == 0)) {
                    weights.set(0, BigDecimal.ONE);
                }
                List<BigDecimal> percentages = LargestRemainder.percentages(weights);

                assertThat(percentages.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(hundred);
                BigDecimal weightSum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                for (int i = 0; i < n; i++) {
                    // |p_i − 100·w_i/W| < 0,01  ⇔  |p_i·W − 100·w_i| < 0,01·W  (sin dividir)
                    BigDecimal error = percentages.get(i).multiply(weightSum).subtract(hundred.multiply(weights.get(i))).abs();
                    assertThat(error).isLessThan(new BigDecimal("0.01").multiply(weightSum));
                    if (weights.get(i).signum() == 0) {
                        assertThat(percentages.get(i)).isEqualByComparingTo(BigDecimal.ZERO);
                    }
                }
            }
        }
    }
}
