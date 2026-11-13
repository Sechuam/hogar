package com.hogar.expense.domain.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.household.domain.SplitPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Especificación del reparto de gastos (contrato Fase 2, §2; D-009, D-011, D-037). */
@DisplayName("SplitCalculator")
class SplitCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static final UUID ANA = uuid(1);
    private static final UUID BEA = uuid(2);
    private static final UUID CARLOS = uuid(3);
    private static final UUID DANI = uuid(4);

    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n));
    }

    /** Participante que se dio de alta {@code day} días después de T0. */
    private static SplitParticipant member(UUID id, int day, String income) {
        return new SplitParticipant(id, T0.plusSeconds(86_400L * day), new BigDecimal(income));
    }

    private static SplitParticipant member(UUID id, int day) {
        return member(id, day, "0");
    }

    private static List<String> amounts(SplitResult result) {
        return result.shares().stream().map(s -> s.amount().toPlainString()).toList();
    }

    private static List<UUID> users(SplitResult result) {
        return result.shares().stream().map(ShareAllocation::userId).toList();
    }

    private static BigDecimal sum(SplitResult result) {
        return result.shares().stream().map(ShareAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Nested
    @DisplayName("EQUAL")
    class Equal {

        @Test
        @DisplayName("10,00 entre 3 → 3,34 / 3,33 / 3,33: el céntimo al más antiguo")
        void tenBetweenThree() {
            SplitResult result = SplitCalculator.split(new BigDecimal("10.00"), SplitPolicy.EQUAL,
                    List.of(member(CARLOS, 2), member(ANA, 0), member(BEA, 1)));

            assertThat(users(result)).containsExactly(ANA, BEA, CARLOS);
            assertThat(amounts(result)).containsExactly("3.34", "3.33", "3.33");
            assertThat(result.appliedPolicy()).isEqualTo(SplitPolicy.EQUAL);
            assertThat(result.warnings()).isEmpty();
        }

        @Test
        @DisplayName("0,01 entre 2 → 0,01 / 0,00")
        void oneCentBetweenTwo() {
            SplitResult result = SplitCalculator.split(new BigDecimal("0.01"), SplitPolicy.EQUAL,
                    List.of(member(BEA, 1), member(ANA, 0)));

            assertThat(users(result)).containsExactly(ANA, BEA);
            assertThat(amounts(result)).containsExactly("0.01", "0.00");
        }

        @Test
        @DisplayName("100,00 entre 7 → cuatro de 14,29 y tres de 14,28")
        void hundredBetweenSeven() {
            List<SplitParticipant> seven = java.util.stream.IntStream.range(0, 7)
                    .mapToObj(i -> member(uuid(10 + i), i))
                    .toList();

            SplitResult result = SplitCalculator.split(new BigDecimal("100.00"), SplitPolicy.EQUAL, seven);

            assertThat(amounts(result)).containsExactly("14.29", "14.29", "14.29", "14.29", "14.28", "14.28", "14.28");
            assertThat(sum(result)).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("un solo miembro paga el total con peso 1,00")
        void singleMember() {
            SplitResult result = SplitCalculator.split(new BigDecimal("42.5"), SplitPolicy.EQUAL,
                    List.of(member(ANA, 0, "1234.56")));

            assertThat(result.shares()).containsExactly(
                    new ShareAllocation(ANA, new BigDecimal("42.50"), new BigDecimal("1.00")));
        }

        @Test
        @DisplayName("mismo joinedAt → desempata el userId")
        void sameJoinedAtTieBrokenByUserId() {
            SplitResult result = SplitCalculator.split(new BigDecimal("0.05"), SplitPolicy.EQUAL,
                    List.of(member(BEA, 0), member(ANA, 0)));

            assertThat(users(result)).containsExactly(ANA, BEA);
            assertThat(amounts(result)).containsExactly("0.03", "0.02");
        }

        @Test
        @DisplayName("el userId se ordena como texto (bytes sin signo, igual que PostgreSQL), no como long con signo")
        void userIdOrderIsUnsigned() {
            UUID low = UUID.fromString("10000000-0000-0000-0000-000000000000");
            UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
            assertThat(high.compareTo(low)).as("UUID#compareTo usa long con signo").isNegative();

            SplitResult result = SplitCalculator.split(new BigDecimal("0.01"), SplitPolicy.EQUAL,
                    List.of(member(high, 0), member(low, 0)));

            assertThat(users(result)).containsExactly(low, high);
            assertThat(amounts(result)).containsExactly("0.01", "0.00");
        }

        @Test
        @DisplayName("en EQUAL los ingresos se ignoran y todos los pesos son 1,00")
        void incomesAreIgnored() {
            SplitResult result = SplitCalculator.split(new BigDecimal("10.00"), SplitPolicy.EQUAL,
                    List.of(member(ANA, 0, "3000"), member(BEA, 1, "0")));

            assertThat(amounts(result)).containsExactly("5.00", "5.00");
            assertThat(result.shares()).extracting(ShareAllocation::weight)
                    .containsOnly(new BigDecimal("1.00"));
        }
    }

    @Nested
    @DisplayName("PROPORTIONAL")
    class Proportional {

        @Test
        @DisplayName("ingresos 1500 / 1000 con 100,00 → 60,00 / 40,00, peso = ingreso en escala 2")
        void sixtyForty() {
            SplitResult result = SplitCalculator.split(new BigDecimal("100.00"), SplitPolicy.PROPORTIONAL,
                    List.of(member(ANA, 0, "1500"), member(BEA, 1, "1000.00")));

            assertThat(result.appliedPolicy()).isEqualTo(SplitPolicy.PROPORTIONAL);
            assertThat(result.warnings()).isEmpty();
            assertThat(result.shares()).containsExactly(
                    new ShareAllocation(ANA, new BigDecimal("60.00"), new BigDecimal("1500.00")),
                    new ShareAllocation(BEA, new BigDecimal("40.00"), new BigDecimal("1000.00")));
        }

        @Test
        @DisplayName("restos iguales → el céntimo al más antiguo, aunque venga después en la lista")
        void equalRemaindersTieBrokenByJoinedAt() {
            // 10,02 · (1000, 1000, 2000) / 4000 = 2,505 / 2,505 / 5,01 → sobra 1 céntimo con empate
            SplitResult result = SplitCalculator.split(new BigDecimal("10.02"), SplitPolicy.PROPORTIONAL,
                    List.of(member(CARLOS, 2, "2000"), member(BEA, 1, "1000"), member(ANA, 0, "1000")));

            assertThat(users(result)).containsExactly(ANA, BEA, CARLOS);
            assertThat(amounts(result)).containsExactly("2.51", "2.50", "5.01");
        }

        @Test
        @DisplayName("el céntimo va al mayor resto, no al más antiguo")
        void largestRemainderBeatsSeniority() {
            // 10,01 · (1000, 1000, 2000) / 4000 = 2,5025 / 2,5025 / 5,005 → resto mayor: Carlos
            SplitResult result = SplitCalculator.split(new BigDecimal("10.01"), SplitPolicy.PROPORTIONAL,
                    List.of(member(ANA, 0, "1000"), member(BEA, 1, "1000"), member(CARLOS, 2, "2000")));

            assertThat(amounts(result)).containsExactly("2.50", "2.50", "5.01");
        }

        @Test
        @DisplayName("ingreso 0 con otros > 0 → paga 0,00 y no recibe céntimos (D-009)")
        void zeroIncomePaysNothing() {
            SplitResult result = SplitCalculator.split(new BigDecimal("0.01"), SplitPolicy.PROPORTIONAL,
                    List.of(member(ANA, 0, "0"), member(BEA, 1, "1000"), member(CARLOS, 2, "1000")));

            assertThat(result.appliedPolicy()).isEqualTo(SplitPolicy.PROPORTIONAL);
            assertThat(result.warnings()).isEmpty();
            assertThat(amounts(result)).containsExactly("0.00", "0.01", "0.00");
            assertThat(result.shares().get(0).weight()).isEqualTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("todos con ingreso 0 → EQUAL con warning PROPORTIONAL_FALLBACK_NO_INCOME y pesos 1,00")
        void allZeroFallsBackToEqual() {
            SplitResult result = SplitCalculator.split(new BigDecimal("10.00"), SplitPolicy.PROPORTIONAL,
                    List.of(member(ANA, 0, "0"), member(BEA, 1, "0.00"), member(CARLOS, 2, "0")));

            assertThat(result.appliedPolicy()).isEqualTo(SplitPolicy.EQUAL);
            assertThat(result.warnings()).containsExactly(SplitWarning.PROPORTIONAL_FALLBACK_NO_INCOME);
            assertThat(amounts(result)).containsExactly("3.34", "3.33", "3.33");
            assertThat(result.shares()).extracting(ShareAllocation::weight)
                    .containsOnly(new BigDecimal("1.00"));
        }

        @Test
        @DisplayName("4 miembros con ingresos dispares e importe impar")
        void disparateIncomes() {
            SplitResult result = SplitCalculator.split(new BigDecimal("123.45"), SplitPolicy.PROPORTIONAL,
                    List.of(member(ANA, 0, "2345.67"), member(BEA, 1, "1200.00"),
                            member(CARLOS, 2, "0"), member(DANI, 3, "987.65")));

            assertThat(amounts(result)).containsExactly("63.88", "32.68", "0.00", "26.89");
            assertThat(sum(result)).isEqualByComparingTo("123.45");
        }

        @Test
        @DisplayName("un ingreso diminuto puede no llegar a pagar nada, y la suma sigue cuadrando")
        void tinyIncome() {
            SplitResult result = SplitCalculator.split(new BigDecimal("999.99"), SplitPolicy.PROPORTIONAL,
                    List.of(member(ANA, 0, "3100"), member(BEA, 1, "1450.50"),
                            member(CARLOS, 2, "725.25"), member(DANI, 3, "0.01")));

            assertThat(amounts(result)).containsExactly("587.59", "274.93", "137.47", "0.00");
            assertThat(result.shares().get(3).weight()).isEqualTo(new BigDecimal("0.01"));
        }

        @Test
        @DisplayName("los céntimos de los ingresos cuentan: 1000,01 / 999,99 con 100,00 → 50,00 / 50,00")
        void incomesWithCents() {
            // exacto: 50,0005 / 49,9995 → base 50,00 / 49,99, el céntimo va al mayor resto (Bea)
            SplitResult result = SplitCalculator.split(new BigDecimal("100.00"), SplitPolicy.PROPORTIONAL,
                    List.of(member(ANA, 0, "1000.01"), member(BEA, 1, "999.99")));

            assertThat(amounts(result)).containsExactly("50.00", "50.00");
            assertThat(result.shares()).extracting(ShareAllocation::weight)
                    .containsExactly(new BigDecimal("1000.01"), new BigDecimal("999.99"));
        }
    }

    @Nested
    @DisplayName("Validaciones")
    class Validations {

        private final List<SplitParticipant> two = List.of(member(ANA, 0, "1"), member(BEA, 1, "1"));

        @Test
        @DisplayName("total 0 → IllegalArgumentException")
        void zeroTotal() {
            assertThatThrownBy(() -> SplitCalculator.split(BigDecimal.ZERO, SplitPolicy.EQUAL, two))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("total");
        }

        @Test
        @DisplayName("total negativo → IllegalArgumentException")
        void negativeTotal() {
            assertThatThrownBy(() -> SplitCalculator.split(new BigDecimal("-10.00"), SplitPolicy.EQUAL, two))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("total con escala 3 → IllegalArgumentException")
        void totalWithThreeDecimals() {
            assertThatThrownBy(() -> SplitCalculator.split(new BigDecimal("10.001"), SplitPolicy.EQUAL, two))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("decimales");
        }

        @Test
        @DisplayName("total nulo → IllegalArgumentException")
        void nullTotal() {
            assertThatThrownBy(() -> SplitCalculator.split(null, SplitPolicy.EQUAL, two))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("lista vacía → IllegalArgumentException")
        void emptyParticipants() {
            assertThatThrownBy(() -> SplitCalculator.split(BigDecimal.TEN, SplitPolicy.EQUAL, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("participante");
        }

        @Test
        @DisplayName("userIds duplicados → IllegalArgumentException")
        void duplicatedUserIds() {
            List<SplitParticipant> duplicated = List.of(member(ANA, 0), member(ANA, 1));

            assertThatThrownBy(() -> SplitCalculator.split(BigDecimal.TEN, SplitPolicy.EQUAL, duplicated))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ANA.toString());
        }

        @Test
        @DisplayName("ingreso negativo → IllegalArgumentException (al construir el participante)")
        void negativeIncome() {
            assertThatThrownBy(() -> member(ANA, 0, "-0.01"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("income");
        }

        @Test
        @DisplayName("ingreso con fracciones de céntimo → IllegalArgumentException")
        void incomeWithThreeDecimals() {
            List<SplitParticipant> participants = List.of(member(ANA, 0, "1000.005"), member(BEA, 1, "1"));

            assertThatThrownBy(() -> SplitCalculator.split(BigDecimal.TEN, SplitPolicy.PROPORTIONAL, participants))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ingreso");
        }

        @Test
        @DisplayName("política o lista nulas → NullPointerException")
        void nullArguments() {
            assertThatThrownBy(() -> SplitCalculator.split(BigDecimal.TEN, null, two))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SplitCalculator.split(BigDecimal.TEN, SplitPolicy.EQUAL, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
