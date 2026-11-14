package com.hogar.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hogar.expense.dto.ExpenseTotals;
import com.hogar.expense.service.ExpenseQueries;
import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.ActiveMember;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.settlement.dto.BalanceResponse;
import com.hogar.settlement.dto.MemberBalance;
import com.hogar.settlement.dto.SuggestedTransfer;
import com.hogar.settlement.dto.SuggestedTransfersResponse;
import com.hogar.settlement.repository.SettlementRepository;
import com.hogar.settlement.repository.SettlementUserAmount;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private MembershipGuard guard;
    @Mock
    private HouseholdQueries households;
    @Mock
    private ExpenseQueries expenses;
    @Mock
    private SettlementRepository settlements;

    private BalanceService service;
    private final UUID householdId = UUID.randomUUID();

    /** IDs con orden textual conocido: el orden de alta (ana, bob) es el inverso del textual. */
    private final UUID ana = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000002");
    /** Ex-miembros: "0a…" < "0b…" < "0c…" como texto. */
    private final UUID exA = UUID.fromString("0a000000-0000-0000-0000-000000000000");
    private final UUID exB = UUID.fromString("0b000000-0000-0000-0000-000000000000");
    private final UUID exC = UUID.fromString("0c000000-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        service = new BalanceService(guard, households, expenses, settlements);
    }

    private void household(String currency, UUID... active) {
        List<ActiveMember> members = new java.util.ArrayList<>();
        for (int i = 0; i < active.length; i++) {
            members.add(new ActiveMember(active[i], i == 0 ? HouseholdRole.OWNER : HouseholdRole.MEMBER,
                    T0.plusSeconds(i)));
        }
        when(households.getSplitInfo(householdId))
                .thenReturn(new HouseholdSplitInfo(householdId, SplitPolicy.EQUAL, currency, members));
    }

    private void data(Map<UUID, ExpenseTotals> totals, List<SettlementUserAmount> sent,
                      List<SettlementUserAmount> received) {
        when(expenses.totalsByUser(householdId)).thenReturn(totals);
        when(settlements.sumSentByUser(householdId)).thenReturn(sent);
        when(settlements.sumReceivedByUser(householdId)).thenReturn(received);
    }

    private static ExpenseTotals totals(String paid, String owed) {
        return new ExpenseTotals(new BigDecimal(paid), new BigDecimal(owed));
    }

    private static SettlementUserAmount amount(UUID userId, String total) {
        return new Row(userId, new BigDecimal(total));
    }

    private record Row(UUID getUserId, BigDecimal getTotal) implements SettlementUserAmount {
    }

    @Test
    void outsiderGetsNotFoundBeforeAnyCalculation() {
        doThrow(new NotFoundException("household-not-found", "x")).when(guard).requireMember(householdId, ana);

        assertThatThrownBy(() -> service.balances(householdId, ana)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.suggestions(householdId, ana)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(households, expenses, settlements);
    }

    @Test
    void emptyHouseholdListsActiveMembersAtZeroAndIsSettled() {
        household("USD", ana, bob);
        data(Map.of(), List.of(), List.of());

        BalanceResponse response = service.balances(householdId, ana);

        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.settled()).isTrue();
        assertThat(response.members()).extracting(MemberBalance::userId, MemberBalance::active,
                        m -> m.paid().toPlainString(), m -> m.balance().toPlainString())
                .containsExactly(tuple(ana, true, "0.00", "0.00"), tuple(bob, true, "0.00", "0.00"));
    }

    @Test
    void activeMembersByJoinDateThenFormerMembersWithMovementsByUserIdText() {
        household("EUR", ana, bob);
        Map<UUID, ExpenseTotals> totals = new HashMap<>();
        totals.put(ana, totals("30.00", "10.00"));   // +20
        totals.put(exC, totals("0.00", "10.00"));    // −10
        totals.put(exA, totals("0.00", "10.00"));    // −10
        totals.put(exB, totals("0.00", "0.00"));     // sin movimientos: no aparece
        data(totals, List.of(amount(exA, "4.00")), List.of(amount(ana, "4.00")));

        BalanceResponse response = service.balances(householdId, bob);

        assertThat(response.members()).extracting(MemberBalance::userId, MemberBalance::active)
                .containsExactly(tuple(ana, true), tuple(bob, true), tuple(exA, false), tuple(exC, false));
        assertThat(response.members()).extracting(m -> m.balance().toPlainString())
                .containsExactly("16.00", "0.00", "-6.00", "-10.00");
        MemberBalance exABalance = response.members().get(2);
        assertThat(exABalance.settlementsSent().toPlainString()).isEqualTo("4.00");
        assertThat(exABalance.settlementsReceived().toPlainString()).isEqualTo("0.00");
        assertThat(response.members().get(0).settlementsReceived().toPlainString()).isEqualTo("4.00");
        assertThat(response.settled()).isFalse();
        assertThat(response.members().stream().map(MemberBalance::balance).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0");
    }

    @Test
    void formerMemberWhoSettledEverythingStillAppearsWithZeroBalance() {
        household("EUR", ana);
        data(Map.of(ana, totals("10.00", "5.00"), exA, totals("0.00", "5.00")),
                List.of(amount(exA, "5.00")), List.of(amount(ana, "5.00")));

        BalanceResponse response = service.balances(householdId, ana);

        assertThat(response.members()).extracting(MemberBalance::userId, MemberBalance::active,
                        m -> m.balance().toPlainString())
                .containsExactly(tuple(ana, true, "0.00"), tuple(exA, false, "0.00"));
        assertThat(response.settled()).isTrue();
    }

    @Test
    void suggestionsIncludeFormerMembersAndUseHouseholdCurrency() {
        household("GBP", ana, bob);
        data(Map.of(ana, totals("30.00", "10.00"), bob, totals("0.00", "10.00"), exA, totals("0.00", "10.00")),
                List.of(), List.of());

        SuggestedTransfersResponse response = service.suggestions(householdId, bob);

        assertThat(response.currency()).isEqualTo("GBP");
        // Empate de deuda (10.00): paga primero el userId menor como texto (bob "00…" < exA "0a…").
        assertThat(response.transfers()).extracting(SuggestedTransfer::fromUserId, SuggestedTransfer::toUserId,
                        t -> t.amount().toPlainString())
                .containsExactly(tuple(bob, ana, "10.00"), tuple(exA, ana, "10.00"));
    }

    @Test
    void settledHouseholdHasNoSuggestions() {
        household("EUR", ana, bob);
        data(Map.of(ana, totals("10.00", "5.00"), bob, totals("0.00", "5.00")),
                List.of(amount(bob, "5.00")), List.of(amount(ana, "5.00")));

        assertThat(service.suggestions(householdId, ana).transfers()).isEmpty();
    }

    @Test
    void inconsistentDataIsABugNotAClientError() {
        household("EUR", ana, bob);
        data(Map.of(ana, totals("10.00", "5.00")), List.of(), List.of());

        assertThatThrownBy(() -> service.balances(householdId, ana)).isInstanceOf(IllegalStateException.class);
    }
}
