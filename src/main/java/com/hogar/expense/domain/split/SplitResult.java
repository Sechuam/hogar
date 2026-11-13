package com.hogar.expense.domain.split;

import com.hogar.household.domain.SplitPolicy;
import java.util.List;
import java.util.Set;

/**
 * Resultado del reparto.
 *
 * @param appliedPolicy política realmente aplicada
 * @param shares        una por participante, en orden de desempate (alta en el hogar, userId);
 *                      la suma de {@code amount} es exactamente el total
 */
public record SplitResult(SplitPolicy appliedPolicy, List<ShareAllocation> shares, Set<SplitWarning> warnings) {

    public SplitResult {
        shares = List.copyOf(shares);
        warnings = Set.copyOf(warnings);
    }
}
