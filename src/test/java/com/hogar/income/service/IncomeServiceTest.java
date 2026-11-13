package com.hogar.income.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.ActiveMember;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.income.domain.Income;
import com.hogar.income.dto.IncomeRequest;
import com.hogar.income.dto.IncomeResponse;
import com.hogar.income.dto.MonthlyIncomeSummary;
import com.hogar.income.dto.MonthlyIncomeSummary.MemberIncome;
import com.hogar.income.repository.IncomeRepository;
import com.hogar.income.repository.IncomeRepository.UserIncomeTotal;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class IncomeServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Mock
    private IncomeRepository incomes;
    @Mock
    private IncomeQueries incomeQueries;
    @Mock
    private MembershipGuard guard;
    @Mock
    private HouseholdQueries households;

    private IncomeService service;
    private final UUID householdId = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();
    private final UUID eva = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new IncomeService(incomes, incomeQueries, guard, households, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(households.getSplitInfo(householdId)).thenReturn(info(ana, luis, eva));
    }

    private HouseholdSplitInfo info(UUID... members) {
        List<ActiveMember> active = new java.util.ArrayList<>();
        for (int i = 0; i < members.length; i++) {
            active.add(new ActiveMember(members[i], i == 0 ? HouseholdRole.OWNER : HouseholdRole.MEMBER,
                    NOW.minusSeconds(3600 - i)));
        }
        return new HouseholdSplitInfo(householdId, SplitPolicy.PROPORTIONAL, "EUR", active);
    }

    private static IncomeRequest request(String amount) {
        return new IncomeRequest(new BigDecimal(amount), "Nómina", LocalDate.of(2026, 3, 1), true, null);
    }

    private Income existing(UUID owner) {
        Income income = Income.create(householdId, owner, new BigDecimal("1000"), "Nómina", LocalDate.of(2026, 3, 1),
                false, null, NOW.minusSeconds(60));
        when(incomes.findByIdAndHouseholdId(income.getId(), householdId)).thenReturn(Optional.of(income));
        return income;
    }

    private static String code(Throwable e) {
        return ((DomainException) e).getCode();
    }

    @Test
    void createsIncomeOfTheAuthenticatedUserWithHouseholdCurrency() {
        when(incomes.save(any(Income.class))).thenAnswer(inv -> inv.getArgument(0));

        IncomeResponse response = service.create(householdId, luis, request("1500.5"));

        assertThat(response.userId()).isEqualTo(luis);
        assertThat(response.amount()).isEqualByComparingTo("1500.50");
        assertThat(response.amount().scale()).isEqualTo(2);
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.recurring()).isTrue();
        assertThat(response.createdAt()).isEqualTo(NOW);
        verify(guard).requireMember(householdId, luis);
    }

    @Test
    void nonMemberIsRejectedBeforeTouchingData() {
        UUID stranger = UUID.randomUUID();
        doThrow(new NotFoundException("household-not-found", "x")).when(guard).requireMember(householdId, stranger);

        assertThatThrownBy(() -> service.create(householdId, stranger, request("10")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.summary(householdId, stranger, MARCH))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(incomes, incomeQueries, households);
    }

    @Test
    void listsWithFiltersAndCurrency() {
        Income income = Income.create(householdId, ana, new BigDecimal("10"), "Extra", LocalDate.of(2026, 3, 2),
                false, null, NOW);
        when(incomes.search(householdId, ana, MARCH, PageRequest.of(1, 5)))
                .thenReturn(new PageImpl<>(List.of(income), PageRequest.of(1, 5), 6));

        PageResponse<IncomeResponse> page = service.list(householdId, luis, ana, MARCH, 1, 5);

        assertThat(page.content()).extracting(IncomeResponse::id).containsExactly(income.getId());
        assertThat(page.content().get(0).currency()).isEqualTo("EUR");
        assertThat(page.totalElements()).isEqualTo(6);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void getsIncomeOfAnyMemberButNotOfAnotherHousehold() {
        Income income = existing(ana);
        assertThat(service.get(householdId, luis, income.getId()).id()).isEqualTo(income.getId());

        UUID foreign = UUID.randomUUID();
        when(incomes.findByIdAndHouseholdId(foreign, householdId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(householdId, luis, foreign))
                .isInstanceOf(NotFoundException.class)
                .extracting(IncomeServiceTest::code).isEqualTo("income-not-found");
    }

    @Test
    void ownerUpdatesWithoutCallingSave() {
        Income income = existing(ana);

        IncomeResponse response = service.update(householdId, ana, income.getId(),
                new IncomeRequest(new BigDecimal("2000"), "Nómina nueva", LocalDate.of(2026, 4, 1), true,
                        LocalDate.of(2026, 12, 31)));

        assertThat(response.amount()).isEqualByComparingTo("2000.00");
        assertThat(income.getConcept()).isEqualTo("Nómina nueva");
        assertThat(income.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(income.getUpdatedAt()).isEqualTo(NOW);
        verify(incomes, never()).save(any());
    }

    @Test
    void otherMemberCannotUpdateOrDelete() {
        Income income = existing(ana);

        assertThatThrownBy(() -> service.update(householdId, luis, income.getId(), request("1")))
                .isInstanceOf(ForbiddenException.class)
                .extracting(IncomeServiceTest::code).isEqualTo("income-owner-required");
        assertThatThrownBy(() -> service.delete(householdId, luis, income.getId()))
                .isInstanceOf(ForbiddenException.class)
                .extracting(IncomeServiceTest::code).isEqualTo("income-owner-required");
        assertThat(income.getAmount()).isEqualByComparingTo("1000");
        verify(incomes, never()).delete(any(Income.class));
    }

    @Test
    void ownerDeletes() {
        Income income = existing(ana);

        service.delete(householdId, ana, income.getId());

        verify(incomes).delete(income);
    }

    @Test
    void summaryIncludesEveryActiveMemberAndPercentagesAddUpTo100() {
        UUID formerMember = UUID.randomUUID();
        when(incomeQueries.monthlyIncomeByUser(householdId, MARCH)).thenReturn(Map.of(
                ana, new BigDecimal("1000.00"), luis, new BigDecimal("1000.00"), eva, new BigDecimal("1000.00"),
                formerMember, new BigDecimal("5000.00")));

        MonthlyIncomeSummary summary = service.summary(householdId, ana, MARCH);

        assertThat(summary.month()).isEqualTo(MARCH);
        assertThat(summary.currency()).isEqualTo("EUR");
        assertThat(summary.total()).isEqualByComparingTo("3000.00");
        assertThat(summary.members()).extracting(MemberIncome::userId).containsExactly(ana, luis, eva);
        // El céntimo sobrante va al miembro más antiguo (mismo desempate que el reparto).
        assertThat(summary.members()).extracting(MemberIncome::percentage)
                .containsExactly(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33"));
        assertThat(sumOfPercentages(summary)).isEqualByComparingTo("100.00");
    }

    @Test
    void summaryGivesZeroToMembersWithoutIncome() {
        when(incomeQueries.monthlyIncomeByUser(householdId, MARCH))
                .thenReturn(Map.of(ana, new BigDecimal("2000.00"), luis, new BigDecimal("1000.00")));

        MonthlyIncomeSummary summary = service.summary(householdId, ana, MARCH);

        assertThat(summary.members()).extracting(MemberIncome::amount)
                .containsExactly(new BigDecimal("2000.00"), new BigDecimal("1000.00"), new BigDecimal("0.00"));
        assertThat(summary.members()).extracting(MemberIncome::percentage)
                .containsExactly(new BigDecimal("66.67"), new BigDecimal("33.33"), new BigDecimal("0.00"));
    }

    @Test
    void summaryWithoutIncomeIsAllZeros() {
        when(incomeQueries.monthlyIncomeByUser(householdId, MARCH)).thenReturn(Map.of());

        MonthlyIncomeSummary summary = service.summary(householdId, ana, MARCH);

        assertThat(summary.total()).isEqualByComparingTo("0").extracting(BigDecimal::scale).isEqualTo(2);
        assertThat(summary.members()).hasSize(3).allSatisfy(m -> {
            assertThat(m.amount()).isEqualTo(new BigDecimal("0.00"));
            assertThat(m.percentage()).isEqualTo(new BigDecimal("0.00"));
        });
    }

    @Test
    void queryServiceMapsTheAggregatedSumsWithScale2() {
        UserIncomeTotal row = new UserIncomeTotal() {
            @Override
            public UUID getUserId() {
                return ana;
            }

            @Override
            public BigDecimal getTotal() {
                return new BigDecimal("1500.5");
            }
        };
        when(incomes.sumByUserForMonth(householdId, MARCH)).thenReturn(List.of(row));

        Map<UUID, BigDecimal> result = new IncomeQueryService(incomes).monthlyIncomeByUser(householdId, MARCH);

        assertThat(result).containsExactly(Map.entry(ana, new BigDecimal("1500.50")));
    }

    private static BigDecimal sumOfPercentages(MonthlyIncomeSummary summary) {
        return summary.members().stream().map(MemberIncome::percentage).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
