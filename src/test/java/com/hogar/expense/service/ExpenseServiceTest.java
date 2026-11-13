package com.hogar.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.domain.ExpenseShare;
import com.hogar.expense.domain.SharedExpense;
import com.hogar.expense.domain.split.SplitCalculator;
import com.hogar.expense.domain.split.SplitParticipant;
import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.dto.ExpenseResponse.ShareResponse;
import com.hogar.expense.repository.SharedExpenseRepository;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.ActiveMember;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.income.service.IncomeQueries;
import com.hogar.shared.error.BadRequestException;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.DomainException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import com.hogar.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 3, 5);
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Mock
    private SharedExpenseRepository expenses;
    @Mock
    private MembershipGuard guard;
    @Mock
    private HouseholdQueries households;
    @Mock
    private IncomeQueries incomes;

    private ExpenseService service;
    private final UUID householdId = UUID.randomUUID();
    /** OWNER y miembro más antiguo. */
    private final UUID ana = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();
    private final UUID eva = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ExpenseService(expenses, guard, households, incomes, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(expenses.save(any(SharedExpense.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void household(SplitPolicy policy, UUID... members) {
        List<ActiveMember> active = new ArrayList<>();
        for (int i = 0; i < members.length; i++) {
            active.add(new ActiveMember(members[i], i == 0 ? HouseholdRole.OWNER : HouseholdRole.MEMBER,
                    NOW.minusSeconds(3600 - i)));
        }
        when(households.getSplitInfo(householdId))
                .thenReturn(new HouseholdSplitInfo(householdId, policy, "EUR", active));
    }

    private static ExpenseRequest request(String amount, UUID paidBy) {
        return new ExpenseRequest(new BigDecimal(amount), "Compra", ExpenseCategory.GROCERIES, DATE, paidBy);
    }

    /** Gasto EQUAL de 90,00 entre Ana, Luis y Eva, pagado por {@code payer} y creado por {@code creator}. */
    private SharedExpense existing(UUID payer, UUID creator) {
        List<SplitParticipant> participants = List.of(
                new SplitParticipant(ana, NOW.minusSeconds(3600), BigDecimal.ZERO),
                new SplitParticipant(luis, NOW.minusSeconds(3599), BigDecimal.ZERO),
                new SplitParticipant(eva, NOW.minusSeconds(3598), BigDecimal.ZERO));
        SharedExpense expense = SharedExpense.create(householdId, payer, creator, new BigDecimal("90.00"), "EUR",
                "Compra", ExpenseCategory.GROCERIES, DATE,
                SplitCalculator.split(new BigDecimal("90.00"), SplitPolicy.EQUAL, participants),
                NOW.minusSeconds(60));
        lenient().when(expenses.findByIdAndHouseholdId(expense.getId(), householdId)).thenReturn(Optional.of(expense));
        return expense;
    }

    private static Map<UUID, BigDecimal> sharesByUser(ExpenseResponse response) {
        return response.shares().stream().collect(Collectors.toMap(ShareResponse::userId, ShareResponse::amount));
    }

    private static String code(Throwable e) {
        return ((DomainException) e).getCode();
    }

    @Test
    void equalSplitOfAnOddAmountGivesTheExtraCentToTheOldestMember() {
        household(SplitPolicy.EQUAL, ana, luis);

        ExpenseResponse response = service.create(householdId, luis, request("45.01", null));

        assertThat(sharesByUser(response)).containsOnly(
                Map.entry(ana, new BigDecimal("22.51")), Map.entry(luis, new BigDecimal("22.50")));
        assertThat(response.paidBy()).isEqualTo(luis);
        assertThat(response.createdBy()).isEqualTo(luis);
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.splitPolicyApplied()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(response.warnings()).isEmpty();
        assertThat(response.shares()).extracting(ShareResponse::weight).containsOnly(new BigDecimal("1.00"));
        verifyNoInteractions(incomes);
        verify(expenses).save(any(SharedExpense.class));
    }

    @Test
    void proportionalSplitUsesIncomesOfTheExpenseMonthAndMemberWithoutIncomePaysZero() {
        household(SplitPolicy.PROPORTIONAL, ana, luis, eva);
        when(incomes.monthlyIncomeByUser(householdId, MARCH))
                .thenReturn(Map.of(ana, new BigDecimal("2000.00"), luis, new BigDecimal("1000.00")));

        ExpenseResponse response = service.create(householdId, ana, request("300.00", eva));

        assertThat(sharesByUser(response)).containsOnly(Map.entry(ana, new BigDecimal("200.00")),
                Map.entry(luis, new BigDecimal("100.00")), Map.entry(eva, new BigDecimal("0.00")));
        assertThat(response.paidBy()).isEqualTo(eva);
        assertThat(response.createdBy()).isEqualTo(ana);
        assertThat(response.splitPolicyApplied()).isEqualTo(SplitPolicy.PROPORTIONAL);
        assertThat(response.shares()).filteredOn(s -> s.userId().equals(ana))
                .extracting(ShareResponse::weight).containsExactly(new BigDecimal("2000.00"));
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void proportionalWithoutAnyIncomeFallsBackToEqualWithWarning() {
        household(SplitPolicy.PROPORTIONAL, ana, luis, eva);
        when(incomes.monthlyIncomeByUser(householdId, MARCH)).thenReturn(Map.of());

        ExpenseResponse response = service.create(householdId, ana, request("100.00", null));

        assertThat(response.splitPolicyApplied()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(response.warnings()).containsExactly("PROPORTIONAL_FALLBACK_NO_INCOME");
        assertThat(sharesByUser(response)).containsOnly(Map.entry(ana, new BigDecimal("33.34")),
                Map.entry(luis, new BigDecimal("33.33")), Map.entry(eva, new BigDecimal("33.33")));
    }

    @Test
    void payerMustBeAnActiveMember() {
        household(SplitPolicy.EQUAL, ana, luis);

        assertThatThrownBy(() -> service.create(householdId, ana, request("10", UUID.randomUUID())))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ExpenseServiceTest::code).isEqualTo("payer-not-member");
        verify(expenses, never()).save(any());
    }

    @Test
    void nonMemberIsRejectedBeforeTouchingData() {
        UUID stranger = UUID.randomUUID();
        doThrow(new NotFoundException("household-not-found", "x")).when(guard).requireMember(householdId, stranger);

        assertThatThrownBy(() -> service.create(householdId, stranger, request("10", null)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(expenses, households, incomes);
    }

    @Test
    void getOfAnotherHouseholdsExpenseIsNotFound() {
        UUID foreign = UUID.randomUUID();
        when(expenses.findByIdAndHouseholdId(foreign, householdId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(householdId, ana, foreign))
                .isInstanceOf(NotFoundException.class)
                .extracting(ExpenseServiceTest::code).isEqualTo("expense-not-found");
    }

    @Test
    void getReturnsTheStoredSnapshot() {
        SharedExpense expense = existing(luis, luis);

        ExpenseResponse response = service.get(householdId, eva, expense.getId());

        assertThat(response.id()).isEqualTo(expense.getId());
        assertThat(response.shares()).hasSize(3);
        verifyNoInteractions(households, incomes);
    }

    @Test
    void listRejectsAnInvertedDateRange() {
        assertThatThrownBy(() -> service.list(householdId, ana, DATE, DATE.minusDays(1), null, null, 0, 20))
                .isInstanceOf(BadRequestException.class)
                .extracting(ExpenseServiceTest::code).isEqualTo("invalid-date-range");
        verifyNoInteractions(expenses);
    }

    @Test
    void listPassesFiltersAndPaging() {
        SharedExpense expense = existing(ana, ana);
        when(expenses.search(householdId, DATE, DATE, ExpenseCategory.GROCERIES, ana, PageRequest.of(2, 10)))
                .thenReturn(new PageImpl<>(List.of(expense), PageRequest.of(2, 10), 21));

        PageResponse<ExpenseResponse> page = service.list(householdId, luis, DATE, DATE, ExpenseCategory.GROCERIES,
                ana, 2, 10);

        assertThat(page.content()).extracting(ExpenseResponse::id).containsExactly(expense.getId());
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void changingOnlyDescriptionAndCategoryKeepsTheShares() {
        SharedExpense expense = existing(luis, luis);
        List<ExpenseShare> before = List.copyOf(expense.getShares());
        household(SplitPolicy.PROPORTIONAL, ana, luis, eva);

        ExpenseResponse response = service.update(householdId, luis, expense.getId(), new ExpenseRequest(
                new BigDecimal("90.000"), "Compra semanal", ExpenseCategory.CLEANING, DATE, null));

        assertThat(response.description()).isEqualTo("Compra semanal");
        assertThat(response.category()).isEqualTo(ExpenseCategory.CLEANING);
        assertThat(expense.getShares()).containsExactlyElementsOf(before);
        assertThat(response.splitPolicyApplied()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        verifyNoInteractions(incomes);
        verify(expenses, never()).save(any());
    }

    @Test
    void changingTheAmountRecalculatesWithCurrentPolicyAndIncomes() {
        SharedExpense expense = existing(luis, luis);
        List<ExpenseShare> before = List.copyOf(expense.getShares());
        household(SplitPolicy.PROPORTIONAL, ana, luis, eva);
        LocalDate april = LocalDate.of(2026, 4, 2);
        when(incomes.monthlyIncomeByUser(householdId, YearMonth.of(2026, 4)))
                .thenReturn(Map.of(ana, new BigDecimal("1000.00"), eva, new BigDecimal("3000.00")));

        ExpenseResponse response = service.update(householdId, luis, expense.getId(), new ExpenseRequest(
                new BigDecimal("100.00"), "Compra", ExpenseCategory.GROCERIES, april, null));

        assertThat(response.amount()).isEqualByComparingTo("100.00");
        assertThat(response.date()).isEqualTo(april);
        assertThat(response.splitPolicyApplied()).isEqualTo(SplitPolicy.PROPORTIONAL);
        assertThat(sharesByUser(response)).containsOnly(Map.entry(ana, new BigDecimal("25.00")),
                Map.entry(luis, new BigDecimal("0.00")), Map.entry(eva, new BigDecimal("75.00")));
        assertThat(expense.getShares()).doesNotContainAnyElementsOf(before);
        verify(expenses, never()).save(any());
    }

    @Test
    void changingThePayerRecalculatesAndValidatesTheNewPayer() {
        SharedExpense expense = existing(luis, luis);
        household(SplitPolicy.EQUAL, ana, luis);

        assertThatThrownBy(() -> service.update(householdId, luis, expense.getId(),
                request("90.00", UUID.randomUUID())))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ExpenseServiceTest::code).isEqualTo("payer-not-member");

        ExpenseResponse response = service.update(householdId, luis, expense.getId(), request("90.00", ana));

        assertThat(response.paidBy()).isEqualTo(ana);
        // Eva ya no está entre los miembros actuales: el recálculo solo incluye a Ana y Luis.
        assertThat(sharesByUser(response)).containsOnly(
                Map.entry(ana, new BigDecimal("45.00")), Map.entry(luis, new BigDecimal("45.00")));
    }

    @Test
    void memberWhoIsNeitherPayerNorCreatorCannotEditOrDelete() {
        SharedExpense expense = existing(luis, luis);
        household(SplitPolicy.EQUAL, ana, luis, eva);

        assertThatThrownBy(() -> service.update(householdId, eva, expense.getId(), request("10", null)))
                .isInstanceOf(ForbiddenException.class)
                .extracting(ExpenseServiceTest::code).isEqualTo("expense-edit-forbidden");
        assertThatThrownBy(() -> service.delete(householdId, eva, expense.getId()))
                .isInstanceOf(ForbiddenException.class)
                .extracting(ExpenseServiceTest::code).isEqualTo("expense-edit-forbidden");
        assertThat(expense.getAmount()).isEqualByComparingTo("90.00");
        verify(expenses, never()).delete(any(SharedExpense.class));
    }

    @Test
    void creatorWhoIsNotThePayerCanEdit() {
        SharedExpense expense = existing(luis, eva);
        household(SplitPolicy.EQUAL, ana, luis, eva);

        ExpenseResponse response = service.update(householdId, eva, expense.getId(),
                new ExpenseRequest(new BigDecimal("90"), "Otra", ExpenseCategory.GROCERIES, DATE, luis));

        assertThat(response.description()).isEqualTo("Otra");
    }

    @Test
    void ownerCanDeleteAnyExpense() {
        SharedExpense expense = existing(luis, luis);
        household(SplitPolicy.EQUAL, ana, luis, eva);

        service.delete(householdId, ana, expense.getId());

        verify(expenses).delete(expense);
    }

    @Test
    void payerCanDelete() {
        SharedExpense expense = existing(luis, eva);
        household(SplitPolicy.EQUAL, ana, luis, eva);

        service.delete(householdId, luis, expense.getId());

        verify(expenses).delete(expense);
    }
}
