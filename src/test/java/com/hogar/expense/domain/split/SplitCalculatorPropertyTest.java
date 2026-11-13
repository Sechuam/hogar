package com.hogar.expense.domain.split;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.SplitPolicy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Propiedades del reparto sobre miles de casos aleatorios reproducibles (semilla fija):
 * 1–8 participantes, importes 0,01–1 000 000,00, ingresos con ceros, empates de {@code joinedAt}.
 */
@DisplayName("SplitCalculator: propiedades con casos aleatorios")
class SplitCalculatorPropertyTest {

    private static final int ITERATIONS = 10_000;
    private static final long SEED = 20_261_113L;
    private static final long MAX_CENTS = 1_000_000_00L;
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    @ParameterizedTest(name = "{0}")
    @EnumSource(SplitPolicy.class)
    @DisplayName("Σ shares == total, shares ≥ 0, error < 0,01, determinismo y peso 0 → 0,00")
    void invariantsHold(SplitPolicy policy) {
        Random random = new Random(SEED + policy.ordinal());
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            BigDecimal total = BigDecimal.valueOf(random.nextLong(1, MAX_CENTS + 1), 2);
            List<SplitParticipant> participants = randomParticipants(random);

            SplitResult result = SplitCalculator.split(total, policy, participants);
            String context = "caso %d: total=%s, policy=%s, participantes=%s".formatted(iteration, total, policy, participants);

            assertSumEqualsTotal(result, total, context);
            assertEachShareIsCloseToExact(result, total, context);
            if (result.appliedPolicy() == SplitPolicy.EQUAL) {
                assertEqualSharesDifferByAtMostOneCent(result, context);
            } else {
                assertZeroWeightPaysNothing(result, context);
            }
            assertDeterministic(result, total, policy, participants, random, context);
        }
    }

    private static List<SplitParticipant> randomParticipants(Random random) {
        int n = 1 + random.nextInt(8);
        List<SplitParticipant> participants = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // pocos días distintos → muchos empates de joinedAt que desempata el userId
            Instant joinedAt = T0.plusSeconds(86_400L * random.nextInt(3));
            BigDecimal income = random.nextInt(3) == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(random.nextLong(1, 1_000_000L), 2);
            participants.add(new SplitParticipant(new UUID(random.nextLong(), random.nextLong()), joinedAt, income));
        }
        return participants;
    }

    private static void assertSumEqualsTotal(SplitResult result, BigDecimal total, String context) {
        BigDecimal sum = result.shares().stream().map(ShareAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).as(context).isEqualByComparingTo(total);
    }

    /** |share_i − total·w_i/W| < 0,01, comprobado en enteros: |share_i·W − T·w_i| < W (céntimos). */
    private static void assertEachShareIsCloseToExact(SplitResult result, BigDecimal total, String context) {
        BigInteger totalCents = total.unscaledValue();
        BigInteger weightSum = result.shares().stream()
                .map(s -> s.weight().unscaledValue())
                .reduce(BigInteger.ZERO, BigInteger::add);
        for (ShareAllocation share : result.shares()) {
            assertThat(share.amount().signum()).as(context).isNotNegative();
            assertThat(share.amount().scale()).as(context).isEqualTo(2);
            assertThat(share.weight().scale()).as(context).isEqualTo(2);
            BigInteger error = share.amount().unscaledValue().multiply(weightSum)
                    .subtract(totalCents.multiply(share.weight().unscaledValue()))
                    .abs();
            assertThat(error).as(context).isLessThan(weightSum);
        }
    }

    private static void assertEqualSharesDifferByAtMostOneCent(SplitResult result, String context) {
        List<BigDecimal> amounts = result.shares().stream().map(ShareAllocation::amount).toList();
        BigDecimal spread = Collections.max(amounts).subtract(Collections.min(amounts));
        assertThat(spread).as(context).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    private static void assertZeroWeightPaysNothing(SplitResult result, String context) {
        result.shares().stream()
                .filter(s -> s.weight().signum() == 0)
                .forEach(s -> assertThat(s.amount()).as(context).isEqualTo(new BigDecimal("0.00")));
    }

    private static void assertDeterministic(SplitResult result, BigDecimal total, SplitPolicy policy,
            List<SplitParticipant> participants, Random random, String context) {
        List<SplitParticipant> shuffled = new ArrayList<>(participants);
        Collections.shuffle(shuffled, random);
        assertThat(SplitCalculator.split(total, policy, shuffled)).as(context).isEqualTo(result);
    }
}
