package com.hogar.income.service;

import com.hogar.income.repository.IncomeRepository;
import com.hogar.income.repository.IncomeRepository.UserIncomeTotal;
import com.hogar.shared.money.Money;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementación de {@link IncomeQueries} con una sola consulta agregada por mes. */
@Service
class IncomeQueryService implements IncomeQueries {

    private final IncomeRepository incomes;

    IncomeQueryService(IncomeRepository incomes) {
        this.incomes = incomes;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> monthlyIncomeByUser(UUID householdId, YearMonth month) {
        return incomes.sumByUserForMonth(householdId, month).stream()
                .collect(Collectors.toUnmodifiableMap(UserIncomeTotal::getUserId,
                        t -> Money.requireNonNegativeAmount(t.getTotal())));
    }
}
