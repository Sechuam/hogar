package com.hogar.expense.service;

import com.hogar.expense.domain.SharedExpense;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.shared.error.ForbiddenException;
import java.util.UUID;

/** Quién puede modificar un gasto (y lo que cuelga de él): pagador, creador u OWNER. */
final class ExpensePermissions {

    static final String EXPENSE_EDIT_FORBIDDEN = "expense-edit-forbidden";

    private ExpensePermissions() {
    }

    static void requireCanModify(SharedExpense expense, HouseholdSplitInfo info, UUID userId) {
        boolean isOwner = info.activeMembers().stream()
                .anyMatch(m -> m.userId().equals(userId) && m.role() == HouseholdRole.OWNER);
        if (!isOwner && !userId.equals(expense.getPaidBy()) && !userId.equals(expense.getCreatedBy())) {
            throw new ForbiddenException(EXPENSE_EDIT_FORBIDDEN,
                    "Solo quien pagó o registró el gasto, o un OWNER, puede modificarlo.");
        }
    }
}
