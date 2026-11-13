package com.hogar.expense.domain.split;

import com.hogar.household.domain.SplitPolicy;
import com.hogar.shared.money.LargestRemainder;
import com.hogar.shared.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reparto de un gasto entre los participantes según la política del hogar (contrato Fase 2, §2).
 *
 * <p>Clase pura y determinista: sin Spring, sin BD, sin reloj. Trabaja en céntimos con aritmética
 * entera exacta y asigna los céntimos sobrantes por mayor resto (D-011, D-037), con desempate por
 * antigüedad en el hogar y luego {@code userId}.
 */
public final class SplitCalculator {

    private static final BigDecimal EQUAL_WEIGHT = BigDecimal.ONE.setScale(Money.SCALE);

    /**
     * Orden estable: {@code joinedAt} asc y luego {@code userId} asc. El {@code userId} se compara
     * por su forma canónica en texto, que equivale al orden de bytes sin signo (el de PostgreSQL),
     * y no con {@link UUID#compareTo}, que compara {@code long} con signo.
     */
    static final Comparator<SplitParticipant> TIE_BREAK_ORDER = Comparator
            .comparing(SplitParticipant::joinedAt)
            .thenComparing(p -> p.userId().toString());

    private SplitCalculator() {
    }

    /**
     * Reparte {@code total} entre {@code participants}.
     *
     * @param total        importe &gt; 0 con como mucho 2 decimales
     * @param policy       política del hogar; PROPORTIONAL sin ingresos cae a EQUAL con warning
     * @param participants al menos uno, sin {@code userId} repetidos, ingresos en céntimos
     * @return shares en orden de desempate cuya suma es exactamente {@code total}
     * @throws IllegalArgumentException si algún argumento no cumple lo anterior
     */
    public static SplitResult split(BigDecimal total, SplitPolicy policy, List<SplitParticipant> participants) {
        BigDecimal amount = requireTotal(total);
        Objects.requireNonNull(policy, "policy");
        List<SplitParticipant> ordered = orderedAndValidated(participants);

        SplitPolicy applied = policy;
        Set<SplitWarning> warnings = EnumSet.noneOf(SplitWarning.class);
        if (policy == SplitPolicy.PROPORTIONAL && totalIncome(ordered).signum() == 0) {
            applied = SplitPolicy.EQUAL;
            warnings.add(SplitWarning.PROPORTIONAL_FALLBACK_NO_INCOME);
        }

        List<BigDecimal> weights = weightsFor(applied, ordered);
        List<BigDecimal> amounts = LargestRemainder.allocate(amount, weights);

        List<ShareAllocation> shares = new ArrayList<>(ordered.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < ordered.size(); i++) {
            shares.add(new ShareAllocation(ordered.get(i).userId(), amounts.get(i), weights.get(i)));
            sum = sum.add(amounts.get(i));
        }
        if (sum.compareTo(amount) != 0) {
            throw new IllegalStateException("invariante rota: Σ shares " + sum + " != total " + amount);
        }
        return new SplitResult(applied, shares, warnings);
    }

    private static BigDecimal requireTotal(BigDecimal total) {
        try {
            return Money.requirePositiveAmount(total);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("total inválido: " + e.getMessage(), e);
        }
    }

    private static List<SplitParticipant> orderedAndValidated(List<SplitParticipant> participants) {
        Objects.requireNonNull(participants, "participants");
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("hace falta al menos un participante");
        }
        Set<UUID> seen = new HashSet<>();
        for (SplitParticipant p : participants) {
            Objects.requireNonNull(p, "participant");
            if (!seen.add(p.userId())) {
                throw new IllegalArgumentException("userId repetido: " + p.userId());
            }
            try {
                Money.requireNonNegativeAmount(p.income());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("ingreso inválido de " + p.userId() + ": " + e.getMessage(), e);
            }
        }
        return participants.stream().sorted(TIE_BREAK_ORDER).toList();
    }

    private static BigDecimal totalIncome(List<SplitParticipant> participants) {
        return participants.stream().map(SplitParticipant::income).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Pesos con escala 2: 1.00 en EQUAL, el ingreso del mes en PROPORTIONAL. */
    private static List<BigDecimal> weightsFor(SplitPolicy policy, List<SplitParticipant> participants) {
        return switch (policy) {
            case EQUAL -> participants.stream().map(p -> EQUAL_WEIGHT).toList();
            case PROPORTIONAL -> participants.stream().map(p -> p.income().setScale(Money.SCALE)).toList();
        };
    }
}
