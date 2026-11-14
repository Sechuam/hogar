package com.hogar.expense.service;

import com.hogar.expense.domain.SharedExpense;
import com.hogar.expense.dto.ExpenseRef;
import com.hogar.expense.repository.SharedExpenseRepository;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.shared.error.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExpenseQueryService implements ExpenseQueries {

    private final SharedExpenseRepository expenses;
    private final HouseholdQueries households;

    ExpenseQueryService(SharedExpenseRepository expenses, HouseholdQueries households) {
        this.expenses = expenses;
        this.households = households;
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseRef require(UUID householdId, UUID expenseId) {
        return toRef(expenses.findByIdAndHouseholdId(expenseId, householdId)
                .orElseThrow(ExpenseQueryService::notFound));
    }

    /** {@code MANDATORY}: un bloqueo fuera de la transacción del llamante no protegería nada. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseRef lockForModification(UUID householdId, UUID expenseId, UUID userId) {
        SharedExpense expense = expenses.findForUpdate(expenseId, householdId)
                .orElseThrow(ExpenseQueryService::notFound);
        ExpensePermissions.requireCanModify(expense, households.getSplitInfo(householdId), userId);
        return toRef(expense);
    }

    private static ExpenseRef toRef(SharedExpense e) {
        return new ExpenseRef(e.getId(), e.getHouseholdId(), e.getPaidBy(), e.getCreatedBy());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(ExpenseService.EXPENSE_NOT_FOUND, "El gasto no existe.");
    }
}
