package com.hogar.expense.service;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.domain.SharedExpense;
import com.hogar.expense.domain.split.SplitCalculator;
import com.hogar.expense.domain.split.SplitParticipant;
import com.hogar.expense.domain.split.SplitResult;
import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.repository.SharedExpenseRepository;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.income.service.IncomeQueries;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import com.hogar.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gastos comunes con su reparto congelado (snapshot). El reparto se calcula al crear (y al
 * recalcular) con la política del hogar, los miembros activos (D-034) y, en PROPORTIONAL, los
 * ingresos del mes natural de la fecha del gasto. El {@code userId} siempre lo pasa el controller.
 */
@Service
public class ExpenseService {

    static final String EXPENSE_NOT_FOUND = "expense-not-found";
    static final String EXPENSE_EDIT_FORBIDDEN = "expense-edit-forbidden";
    static final String PAYER_NOT_MEMBER = "payer-not-member";
    static final String INVALID_DATE_RANGE = "invalid-date-range";

    private final SharedExpenseRepository expenses;
    private final MembershipGuard guard;
    private final HouseholdQueries households;
    private final IncomeQueries incomes;
    private final Clock clock;

    ExpenseService(SharedExpenseRepository expenses, MembershipGuard guard, HouseholdQueries households,
                   IncomeQueries incomes, Clock clock) {
        this.expenses = expenses;
        this.guard = guard;
        this.households = households;
        this.incomes = incomes;
        this.clock = clock;
    }

    /** Registra un gasto; {@code paidBy} nulo = el usuario autenticado. */
    @Transactional
    public ExpenseResponse create(UUID householdId, UUID userId, ExpenseRequest request) {
        guard.requireMember(householdId, userId);
        HouseholdSplitInfo info = households.getSplitInfo(householdId);
        UUID payer = payerOf(request, userId);
        requireActivePayer(info, payer);
        SplitResult split = split(info, request.amount(), request.date());
        SharedExpense expense = SharedExpense.create(householdId, payer, userId, request.amount(), info.currency(),
                request.description(), request.category(), request.date(), split, Instant.now(clock));
        return ExpenseMapper.toResponse(expenses.save(expense));
    }

    /**
     * Gastos del hogar, fecha desc y alta desc. Filtros opcionales; {@code from}/{@code to} inclusivos.
     *
     * @throws InvalidQueryException {@code invalid-date-range} si {@code from > to}
     */
    @Transactional(readOnly = true)
    public PageResponse<ExpenseResponse> list(UUID householdId, UUID userId, LocalDate fromOrNull,
                                              LocalDate toOrNull, ExpenseCategory categoryOrNull,
                                              UUID paidByOrNull, int page, int size) {
        guard.requireMember(householdId, userId);
        if (fromOrNull != null && toOrNull != null && fromOrNull.isAfter(toOrNull)) {
            throw new InvalidQueryException(INVALID_DATE_RANGE, "'from' no puede ser posterior a 'to'.");
        }
        return PageResponse.of(expenses.search(householdId, fromOrNull, toOrNull, categoryOrNull, paidByOrNull,
                PageRequest.of(page, size)), ExpenseMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ExpenseResponse get(UUID householdId, UUID userId, UUID expenseId) {
        guard.requireMember(householdId, userId);
        return ExpenseMapper.toResponse(load(householdId, expenseId));
    }

    /**
     * PUT: reemplaza los datos. Si cambian importe, fecha o pagador, el reparto se recalcula con la
     * política, ingresos y miembros <b>actuales</b>; si no, las partes no se tocan. Solo pagador,
     * creador u OWNER. {@code paidBy} nulo = el usuario autenticado, igual que al crear.
     */
    @Transactional
    public ExpenseResponse update(UUID householdId, UUID userId, UUID expenseId, ExpenseRequest request) {
        guard.requireMember(householdId, userId);
        SharedExpense expense = load(householdId, expenseId);
        HouseholdSplitInfo info = households.getSplitInfo(householdId);
        requireCanModify(expense, info, userId);

        UUID payer = payerOf(request, userId);
        boolean payerChanged = !payer.equals(expense.getPaidBy());
        boolean recalculate = payerChanged
                || request.amount().compareTo(expense.getAmount()) != 0
                || !request.date().equals(expense.getExpenseDate());
        if (payerChanged) {
            requireActivePayer(info, payer);
        }
        Instant now = Instant.now(clock);
        // Entidad gestionada: el flush al cerrar la transacción sincroniza los cambios (sin save()).
        expense.changeDetails(request.description(), request.category(), now);
        if (recalculate) {
            expense.changeAmountDatePayer(request.amount(), request.date(), payer, now);
            expense.replaceShares(split(info, request.amount(), request.date()), now);
        }
        return ExpenseMapper.toResponse(expense);
    }

    /** Solo pagador, creador u OWNER. Las partes se borran en cascada. */
    @Transactional
    public void delete(UUID householdId, UUID userId, UUID expenseId) {
        guard.requireMember(householdId, userId);
        SharedExpense expense = load(householdId, expenseId);
        requireCanModify(expense, households.getSplitInfo(householdId), userId);
        expenses.delete(expense);
    }

    private SplitResult split(HouseholdSplitInfo info, BigDecimal amount, LocalDate date) {
        Map<UUID, BigDecimal> income = info.splitPolicy() == SplitPolicy.PROPORTIONAL
                ? incomes.monthlyIncomeByUser(info.householdId(), YearMonth.from(date))
                : Map.of();
        List<SplitParticipant> participants = info.activeMembers().stream()
                .map(m -> new SplitParticipant(m.userId(), m.joinedAt(),
                        income.getOrDefault(m.userId(), BigDecimal.ZERO)))
                .toList();
        return SplitCalculator.split(amount, info.splitPolicy(), participants);
    }

    private static UUID payerOf(ExpenseRequest request, UUID userId) {
        return request.paidBy() != null ? request.paidBy() : userId;
    }

    private static void requireActivePayer(HouseholdSplitInfo info, UUID payer) {
        if (!info.isActiveMember(payer)) {
            throw new BusinessRuleException(PAYER_NOT_MEMBER, "Quien paga debe ser un miembro activo del hogar.");
        }
    }

    private static void requireCanModify(SharedExpense expense, HouseholdSplitInfo info, UUID userId) {
        boolean isOwner = info.activeMembers().stream()
                .anyMatch(m -> m.userId().equals(userId) && m.role() == HouseholdRole.OWNER);
        if (!isOwner && !userId.equals(expense.getPaidBy()) && !userId.equals(expense.getCreatedBy())) {
            throw new ForbiddenException(EXPENSE_EDIT_FORBIDDEN,
                    "Solo quien pagó o registró el gasto, o un OWNER, puede modificarlo.");
        }
    }

    private SharedExpense load(UUID householdId, UUID expenseId) {
        return expenses.findByIdAndHouseholdId(expenseId, householdId)
                .orElseThrow(() -> new NotFoundException(EXPENSE_NOT_FOUND, "El gasto no existe."));
    }
}
