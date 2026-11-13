package com.hogar.income.service;

import com.hogar.income.domain.Income;
import com.hogar.income.dto.IncomeResponse;

final class IncomeMapper {

    private IncomeMapper() {
    }

    static IncomeResponse toResponse(Income income, String currency) {
        return new IncomeResponse(income.getId(), income.getUserId(), income.getAmount(), currency,
                income.getConcept(), income.getStartDate(), income.isRecurring(), income.getEndDate(),
                income.getCreatedAt());
    }
}
