package com.hogar.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.hogar.household.domain.SplitPolicy;
import com.hogar.settlement.dto.BalanceResponse;
import com.hogar.settlement.dto.MemberBalance;
import com.hogar.settlement.dto.SettlementResponse;
import com.hogar.settlement.dto.SuggestedTransfer;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Balances y pagos sugeridos de punta a punta: HTTP → BD real, comprobados también con SQL. */
class BalanceIT extends SettlementApiSupport {

    @Test
    void newHouseholdIsSettledWithMembersAtZero() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);

        BalanceResponse balances = balances(bob, h);

        assertThat(balances.currency()).isEqualTo("EUR");
        assertThat(balances.settled()).isTrue();
        assertThat(balances.members()).extracting(MemberBalance::userId, MemberBalance::active)
                .containsExactly(tuple(ana.id(), true), tuple(bob.id(), true));
        // Escala 2 también en el JSON: "0.00", nunca "0".
        assertThat(get(householdPath(h) + "/balances", ana).getBody())
                .contains("\"paid\":0.00", "\"balance\":0.00").doesNotContain(":0,", ":0}");
        assertThat(suggested(ana, h).transfers()).isEmpty();
        assertThat(suggested(ana, h).currency()).isEqualTo("EUR");
    }

    @Test
    void twoMembersEqualWithOddCentsAndSettlementThatClosesEverything() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);
        createExpense(ana, h, expense("10.00", MARCH_5, null));
        // 0,01 entre 2: el céntimo va al miembro más antiguo (Ana).
        createExpense(bob, h, expense("0.01", MARCH_5, null));

        BalanceResponse balances = balances(ana, h);

        assertThat(balances.members()).extracting(MemberBalance::userId, m -> m.paid().toPlainString(),
                        m -> m.owed().toPlainString(), m -> m.balance().toPlainString())
                .containsExactly(tuple(ana.id(), "10.00", "5.01", "4.99"), tuple(bob.id(), "0.01", "5.00", "-4.99"));
        assertThat(balances.settled()).isFalse();
        assertMatchesDb(h, balances);
        assertThat(suggested(bob, h).transfers()).containsExactly(
                new SuggestedTransfer(bob.id(), ana.id(), new BigDecimal("4.99")));

        settle(bob, h, bob.id(), ana.id(), "4.99");

        BalanceResponse after = balances(bob, h);
        assertThat(after.settled()).isTrue();
        assertThat(byUser(after)).containsOnly(Map.entry(ana.id(), "0.00"), Map.entry(bob.id(), "0.00"));
        assertThat(after.members()).extracting(m -> m.settlementsSent().toPlainString(),
                m -> m.settlementsReceived().toPlainString()).containsExactly(tuple("0.00", "4.99"), tuple("4.99", "0.00"));
        assertMatchesDb(h, after);
        assertThat(suggested(ana, h).transfers()).isEmpty();
    }

    @Test
    void threeMembersTenEurosBetweenThree() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla);
        createExpense(bob, h, expense("10.00", MARCH_5, null));

        BalanceResponse balances = balances(carla, h);

        // 3,34 / 3,33 / 3,33: el céntimo sobrante, al miembro más antiguo.
        assertThat(byUser(balances)).containsOnly(Map.entry(ana.id(), "-3.34"), Map.entry(bob.id(), "6.67"),
                Map.entry(carla.id(), "-3.33"));
        assertMatchesDb(h, balances);
        assertThat(suggested(ana, h).transfers()).extracting(SuggestedTransfer::fromUserId,
                        SuggestedTransfer::toUserId, t -> t.amount().toPlainString())
                .containsExactly(tuple(ana.id(), bob.id(), "3.34"), tuple(carla.id(), bob.id(), "3.33"));
    }

    @Test
    void fourMembersEqualAndProportionalThenApplyingSuggestionsSettlesTheHousehold() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        TestUser dani = users().create("Dani");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla, dani);

        createExpense(ana, h, expense("10.00", MARCH_5, null));    // 2,50 cada uno
        createExpense(bob, h, expense("0.03", MARCH_5, null));     // 0,01 Ana, Bob y Carla; 0 Dani
        changePolicy(ana, h, SplitPolicy.PROPORTIONAL);
        oneOffIncome(ana, h, "3000.00", MARCH_5);
        oneOffIncome(bob, h, "1000.00", MARCH_5);
        oneOffIncome(dani, h, "1000.00", MARCH_5);                 // Carla: sin ingresos
        createExpense(carla, h, expense("100.00", MARCH_5, null)); // 60 / 20 / 0 / 20
        createExpense(dani, h, expense("10.01", MARCH_5, null));   // 6,01 / 2,00 / 0 / 2,00

        BalanceResponse balances = balances(dani, h);

        assertThat(balances.members()).extracting(MemberBalance::userId, m -> m.paid().toPlainString(),
                        m -> m.owed().toPlainString(), m -> m.balance().toPlainString())
                .containsExactly(
                        tuple(ana.id(), "10.00", "68.52", "-58.52"),
                        tuple(bob.id(), "0.03", "24.51", "-24.48"),
                        tuple(carla.id(), "100.00", "2.51", "97.49"),
                        tuple(dani.id(), "10.01", "24.50", "-14.49"));
        assertMatchesDb(h, balances);

        List<SuggestedTransfer> transfers = suggested(ana, h).transfers();
        assertThat(transfers).extracting(SuggestedTransfer::fromUserId, SuggestedTransfer::toUserId,
                        t -> t.amount().toPlainString())
                .containsExactly(tuple(ana.id(), carla.id(), "58.52"), tuple(bob.id(), carla.id(), "24.48"),
                        tuple(dani.id(), carla.id(), "14.49"));

        applySuggestions(h, transfers, Map.of(ana.id(), ana, bob.id(), bob, carla.id(), carla, dani.id(), dani));

        BalanceResponse after = balances(carla, h);
        assertThat(after.settled()).isTrue();
        assertThat(after.members()).extracting(m -> m.balance().toPlainString()).containsOnly("0.00");
        assertThat(after.members()).filteredOn(m -> m.userId().equals(carla.id()))
                .extracting(m -> m.settlementsReceived().toPlainString()).containsExactly("97.49");
        assertMatchesDb(h, after);
        assertThat(suggested(bob, h).transfers()).isEmpty();
    }

    @Test
    void overpayingFlipsTheSignAndDeletingTheSettlementRestoresTheBalance() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob);
        createExpense(ana, h, expense("20.00", MARCH_5, null));
        assertThat(byUser(balances(ana, h))).containsEntry(bob.id(), "-10.00");

        SettlementResponse overpaid = settle(bob, h, bob.id(), ana.id(), "15.00");

        BalanceResponse flipped = balances(ana, h);
        assertThat(byUser(flipped)).containsOnly(Map.entry(ana.id(), "-5.00"), Map.entry(bob.id(), "5.00"));
        assertThat(flipped.settled()).isFalse();
        assertMatchesDb(h, flipped);
        assertThat(suggested(ana, h).transfers()).containsExactly(
                new SuggestedTransfer(ana.id(), bob.id(), new BigDecimal("5.00")));

        assertThat(deleteSettlement(ana, h, overpaid.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        BalanceResponse restored = balances(bob, h);
        assertThat(byUser(restored)).containsOnly(Map.entry(ana.id(), "10.00"), Map.entry(bob.id(), "-10.00"));
        assertThat(restored.members()).extracting(m -> m.settlementsSent().toPlainString()).containsOnly("0.00");
        assertMatchesDb(h, restored);
    }

    @Test
    void formerMembersWhoLeftOwingStayInBalancesAndSuggestionsAndCanBeSettled() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        TestUser dani = users().create("Dani");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla, dani);
        createExpense(ana, h, expense("40.00", MARCH_5, null)); // 10 cada uno
        leave(bob, h);
        expel(ana, h, carla);

        BalanceResponse balances = balances(dani, h);

        List<TestUser> former = List.of(bob, carla).stream()
                .sorted((a, b) -> a.id().toString().compareTo(b.id().toString())).toList();
        assertThat(balances.members()).extracting(MemberBalance::userId, MemberBalance::active,
                        m -> m.balance().toPlainString())
                .containsExactly(tuple(ana.id(), true, "30.00"), tuple(dani.id(), true, "-10.00"),
                        tuple(former.get(0).id(), false, "-10.00"), tuple(former.get(1).id(), false, "-10.00"));
        assertMatchesDb(h, balances);
        assertThat(suggested(dani, h).transfers()).extracting(SuggestedTransfer::fromUserId,
                        SuggestedTransfer::toUserId, t -> t.amount().toPlainString())
                .containsExactlyInAnyOrder(tuple(bob.id(), ana.id(), "10.00"), tuple(carla.id(), ana.id(), "10.00"),
                        tuple(dani.id(), ana.id(), "10.00"));

        // Los ex-miembros ya no ven el hogar.
        assertProblem(get(householdPath(h) + "/balances", bob), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(settlementsPath(h) + "/suggested", carla), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(settleCall(bob, h, bob.id(), ana.id(), "10.00"), HttpStatus.NOT_FOUND, "household-not-found");

        // Pero se les puede registrar el pago: Ana (receptora) el de Bob, y Dani no puede registrar el de Carla.
        settle(ana, h, bob.id(), ana.id(), "10.00");
        assertProblem(settleCall(dani, h, carla.id(), ana.id(), "10.00"), HttpStatus.FORBIDDEN,
                "settlement-forbidden");

        BalanceResponse after = balances(ana, h);
        assertThat(after.members()).filteredOn(m -> m.userId().equals(bob.id()))
                .extracting(MemberBalance::active, m -> m.balance().toPlainString(),
                        m -> m.settlementsSent().toPlainString())
                .containsExactly(tuple(false, "0.00", "10.00"));
        assertThat(byUser(after)).containsEntry(ana.id(), "20.00").containsEntry(carla.id(), "-10.00");
        assertMatchesDb(h, after);
    }

    @Test
    void formerMemberWithoutMovementsDoesNotAppear() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla);
        leave(carla, h);
        createExpense(ana, h, expense("10.00", MARCH_5, null));

        assertThat(balances(bob, h).members()).extracting(MemberBalance::userId)
                .containsExactly(ana.id(), bob.id());
    }

    @Test
    void balancesOnlyCountTheirOwnHousehold() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        UUID h1 = createHousehold(ana, SplitPolicy.EQUAL);
        UUID h2 = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h1, bob);
        join(ana, h2, bob);
        createExpense(ana, h1, expense("10.00", MARCH_5, null));
        createExpense(bob, h2, expense("50.00", MARCH_5, null));
        settle(bob, h2, ana.id(), bob.id(), "1.00");

        assertThat(byUser(balances(ana, h1))).containsOnly(Map.entry(ana.id(), "5.00"), Map.entry(bob.id(), "-5.00"));
        assertThat(byUser(balances(ana, h2))).containsOnly(Map.entry(ana.id(), "-24.00"),
                Map.entry(bob.id(), "24.00"));
    }
}
