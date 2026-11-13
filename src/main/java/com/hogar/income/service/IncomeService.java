package com.hogar.income.service;

import com.hogar.household.dto.ActiveMember;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.income.domain.Income;
import com.hogar.income.dto.IncomeRequest;
import com.hogar.income.dto.IncomeResponse;
import com.hogar.income.dto.MonthlyIncomeSummary;
import com.hogar.income.dto.MonthlyIncomeSummary.MemberIncome;
import com.hogar.income.repository.IncomeRepository;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.money.LargestRemainder;
import com.hogar.shared.money.Money;
import com.hogar.shared.security.MembershipGuard;
import com.hogar.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingresos de los miembros de un hogar. Todos los miembros ven todos los ingresos (transparencia
 * del reparto proporcional), pero solo el dueño de un ingreso puede editarlo o borrarlo.
 * El {@code userId} siempre lo pasa el controller.
 */
@Service
public class IncomeService {

    static final String INCOME_NOT_FOUND = "income-not-found";
    static final String INCOME_OWNER_REQUIRED = "income-owner-required";

    private static final BigDecimal ZERO = Money.fromCents(0);

    private final IncomeRepository incomes;
    private final IncomeQueries incomeQueries;
    private final MembershipGuard guard;
    private final HouseholdQueries households;
    private final Clock clock;

    IncomeService(IncomeRepository incomes, IncomeQueries incomeQueries, MembershipGuard guard,
                  HouseholdQueries households, Clock clock) {
        this.incomes = incomes;
        this.incomeQueries = incomeQueries;
        this.guard = guard;
        this.households = households;
        this.clock = clock;
    }

    /** Registra un ingreso del usuario autenticado. */
    @Transactional
    public IncomeResponse create(UUID householdId, UUID userId, IncomeRequest request) {
        guard.requireMember(householdId, userId);
        Income income = incomes.save(Income.create(householdId, userId, request.amount(), request.concept(),
                request.date(), request.recurring(), request.endDate(), Instant.now(clock)));
        return IncomeMapper.toResponse(income, currency(householdId));
    }

    /**
     * Ingresos del hogar (de todos los miembros), fecha desc.
     *
     * @param userIdOrNull filtra por miembro
     * @param monthOrNull  solo los que cuentan en ese mes
     */
    @Transactional(readOnly = true)
    public PageResponse<IncomeResponse> list(UUID householdId, UUID userId, UUID userIdOrNull, YearMonth monthOrNull,
                                             int page, int size) {
        guard.requireMember(householdId, userId);
        String currency = currency(householdId);
        return PageResponse.of(incomes.search(householdId, userIdOrNull, monthOrNull, PageRequest.of(page, size)),
                income -> IncomeMapper.toResponse(income, currency));
    }

    @Transactional(readOnly = true)
    public IncomeResponse get(UUID householdId, UUID userId, UUID incomeId) {
        guard.requireMember(householdId, userId);
        return IncomeMapper.toResponse(load(householdId, incomeId), currency(householdId));
    }

    /** PUT: reemplaza los datos. Solo el dueño del ingreso. */
    @Transactional
    public IncomeResponse update(UUID householdId, UUID userId, UUID incomeId, IncomeRequest request) {
        guard.requireMember(householdId, userId);
        Income income = loadOwned(householdId, userId, incomeId);
        // Entidad gestionada: el flush al cerrar la transacción sincroniza el cambio (sin save()).
        income.update(request.amount(), request.concept(), request.date(), request.recurring(), request.endDate(),
                Instant.now(clock));
        return IncomeMapper.toResponse(income, currency(householdId));
    }

    /** Solo el dueño del ingreso. */
    @Transactional
    public void delete(UUID householdId, UUID userId, UUID incomeId) {
        guard.requireMember(householdId, userId);
        incomes.delete(loadOwned(householdId, userId, incomeId));
    }

    /**
     * Ingresos computables de cada miembro activo en {@code month} (también los que no tienen) y su
     * porcentaje sobre el total: suman exactamente 100,00 por mayor resto, con el mismo desempate
     * que el reparto (antigüedad en el hogar); todos 0 si el total es 0. Los ingresos de quien ya no
     * es miembro no cuentan, igual que en el reparto PROPORTIONAL.
     */
    @Transactional(readOnly = true)
    public MonthlyIncomeSummary summary(UUID householdId, UUID userId, YearMonth month) {
        guard.requireMember(householdId, userId);
        HouseholdSplitInfo info = households.getSplitInfo(householdId);
        Map<UUID, BigDecimal> byUser = incomeQueries.monthlyIncomeByUser(householdId, month);

        List<ActiveMember> members = info.activeMembers();
        List<BigDecimal> amounts = members.stream().map(m -> byUser.getOrDefault(m.userId(), ZERO)).toList();
        BigDecimal total = amounts.stream().reduce(ZERO, BigDecimal::add);
        List<BigDecimal> percentages = total.signum() == 0
                ? Collections.nCopies(members.size(), ZERO)
                : LargestRemainder.percentages(amounts);

        List<MemberIncome> result = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            result.add(new MemberIncome(members.get(i).userId(), amounts.get(i), percentages.get(i)));
        }
        return new MonthlyIncomeSummary(month, info.currency(), total, List.copyOf(result));
    }

    private Income loadOwned(UUID householdId, UUID userId, UUID incomeId) {
        Income income = load(householdId, incomeId);
        if (!income.getUserId().equals(userId)) {
            throw new ForbiddenException(INCOME_OWNER_REQUIRED, "Solo quien registró el ingreso puede modificarlo.");
        }
        return income;
    }

    private Income load(UUID householdId, UUID incomeId) {
        return incomes.findByIdAndHouseholdId(incomeId, householdId)
                .orElseThrow(() -> new NotFoundException(INCOME_NOT_FOUND, "El ingreso no existe."));
    }

    private String currency(UUID householdId) {
        return households.getSplitInfo(householdId).currency();
    }
}
