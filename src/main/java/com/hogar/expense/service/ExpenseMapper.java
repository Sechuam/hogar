package com.hogar.expense.service;

import com.hogar.expense.domain.ExpenseShare;
import com.hogar.expense.domain.SharedExpense;
import com.hogar.expense.domain.split.SplitWarning;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.dto.ExpenseResponse.ShareResponse;
import java.util.List;

final class ExpenseMapper {

    private ExpenseMapper() {
    }

    /** Las {@code warnings} salen del {@code split_fallback} persistido: también aparecen al consultar. */
    static ExpenseResponse toResponse(SharedExpense expense) {
        List<ShareResponse> shares = expense.getShares().stream().map(ExpenseMapper::toShare).toList();
        List<String> warnings = expense.isSplitFallback()
                ? List.of(SplitWarning.PROPORTIONAL_FALLBACK_NO_INCOME.name())
                : List.of();
        return new ExpenseResponse(expense.getId(), expense.getAmount(), expense.getCurrency(),
                expense.getDescription(), expense.getCategory(), expense.getExpenseDate(), expense.getPaidBy(),
                expense.getCreatedBy(), expense.getSplitPolicyApplied(), shares, warnings, expense.getCreatedAt(),
                expense.getUpdatedAt());
    }

    private static ShareResponse toShare(ExpenseShare share) {
        return new ShareResponse(share.getUserId(), share.getAmount(), share.getWeight());
    }
}
