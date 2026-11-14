package com.hogar.settlement.service;

import com.hogar.expense.dto.ExpenseTotals;
import com.hogar.expense.service.ExpenseQueries;
import com.hogar.household.dto.ActiveMember;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.settlement.domain.balance.BalanceCalculator;
import com.hogar.settlement.domain.balance.DebtSimplifier;
import com.hogar.settlement.domain.balance.UserBalance;
import com.hogar.settlement.dto.BalanceResponse;
import com.hogar.settlement.dto.MemberBalance;
import com.hogar.settlement.dto.SuggestedTransfer;
import com.hogar.settlement.dto.SuggestedTransfersResponse;
import com.hogar.settlement.repository.SettlementRepository;
import com.hogar.settlement.repository.SettlementUserAmount;
import com.hogar.shared.security.MembershipGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Balances del hogar y pagos sugeridos para saldarlos. Se calculan siempre a partir de los gastos
 * (shares congelados) y las liquidaciones: no se guardan, así que no hay nada que desincronizar.
 *
 * <p>Orden de presentación: miembros activos por fecha de alta (aunque estén a 0) y después
 * ex-miembros con algún movimiento, por {@code userId} como texto (D-041).
 *
 * <p>Las lecturas usan {@code REPEATABLE READ}: el cálculo hace varias sumas en sentencias distintas
 * (pagado, partes, enviado, recibido) y en {@code READ COMMITTED} cada una ve su propia foto. Un
 * gasto editado o creado entre dos de ellas daba Σ pagado ≠ Σ debido → {@code IllegalStateException}
 * → 500. Con una única foto por transacción el resultado siempre cuadra (PostgreSQL no aborta
 * transacciones de solo lectura en este nivel).
 */
@Service
public class BalanceService {

    private final MembershipGuard guard;
    private final HouseholdQueries households;
    private final ExpenseQueries expenses;
    private final SettlementRepository settlements;

    BalanceService(MembershipGuard guard, HouseholdQueries households, ExpenseQueries expenses,
                   SettlementRepository settlements) {
        this.guard = guard;
        this.households = households;
        this.expenses = expenses;
        this.settlements = settlements;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BalanceResponse balances(UUID householdId, UUID userId) {
        guard.requireMember(householdId, userId);
        HouseholdSplitInfo info = households.getSplitInfo(householdId);
        Map<UUID, UserBalance> all = calculate(info);

        List<MemberBalance> members = new ArrayList<>();
        Set<UUID> active = info.activeMembers().stream().map(ActiveMember::userId).collect(Collectors.toSet());
        for (ActiveMember member : info.activeMembers()) {
            members.add(toMemberBalance(all.get(member.userId()), true));
        }
        // calculate() devuelve los usuarios ordenados por userId como texto: es el orden de los ex-miembros.
        for (UserBalance balance : all.values()) {
            if (!active.contains(balance.userId()) && balance.hasMovements()) {
                members.add(toMemberBalance(balance, false));
            }
        }
        boolean settled = all.values().stream().allMatch(UserBalance::isSettled);
        return new BalanceResponse(info.currency(), List.copyOf(members), settled);
    }

    /** Pagos que dejarían el hogar saldado; incluye a los ex-miembros con saldo. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SuggestedTransfersResponse suggestions(UUID householdId, UUID userId) {
        guard.requireMember(householdId, userId);
        HouseholdSplitInfo info = households.getSplitInfo(householdId);
        List<SuggestedTransfer> transfers = DebtSimplifier.simplify(BalanceCalculator.netBalances(calculate(info)))
                .stream()
                .map(t -> new SuggestedTransfer(t.from(), t.to(), t.amount()))
                .toList();
        return new SuggestedTransfersResponse(info.currency(), transfers);
    }

    private Map<UUID, UserBalance> calculate(HouseholdSplitInfo info) {
        UUID householdId = info.householdId();
        List<UUID> active = info.activeMembers().stream().map(ActiveMember::userId).toList();
        Map<UUID, ExpenseTotals> totals = expenses.totalsByUser(householdId);
        return BalanceCalculator.calculate(active, totals, toMap(settlements.sumSentByUser(householdId)),
                toMap(settlements.sumReceivedByUser(householdId)));
    }

    private static Map<UUID, BigDecimal> toMap(List<SettlementUserAmount> rows) {
        Map<UUID, BigDecimal> map = new HashMap<>();
        for (SettlementUserAmount row : rows) {
            map.put(row.getUserId(), row.getTotal());
        }
        return map;
    }

    private static MemberBalance toMemberBalance(UserBalance b, boolean active) {
        return new MemberBalance(b.userId(), active, b.paid(), b.owed(), b.sent(), b.received(), b.balance());
    }
}
