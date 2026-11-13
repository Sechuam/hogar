package com.hogar.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.expense.domain.split.ShareAllocation;
import com.hogar.expense.domain.split.SplitResult;
import com.hogar.expense.domain.split.SplitWarning;
import com.hogar.household.domain.SplitPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedExpenseTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 3, 5);
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    // Elegidos para que el orden con signo de UUID (Java) y el de PostgreSQL (bytes sin signo) difieran.
    private static final UUID ANA = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Test
    void createsWithSplitSnapshot() {
        SharedExpense expense = SharedExpense.create(HOUSEHOLD, ANA, BOB, new BigDecimal("10.5"), "EUR", "  Compra ",
                ExpenseCategory.GROCERIES, DATE, equal("5.25", "5.25"), NOW);

        assertThat(expense.getId()).isNotNull();
        assertThat(expense.getHouseholdId()).isEqualTo(HOUSEHOLD);
        assertThat(expense.getPaidBy()).isEqualTo(ANA);
        assertThat(expense.getCreatedBy()).isEqualTo(BOB);
        assertThat(expense.getAmount()).isEqualTo(new BigDecimal("10.50"));
        assertThat(expense.getCurrency()).isEqualTo("EUR");
        assertThat(expense.getDescription()).isEqualTo("Compra");
        assertThat(expense.getCategory()).isEqualTo(ExpenseCategory.GROCERIES);
        assertThat(expense.getExpenseDate()).isEqualTo(DATE);
        assertThat(expense.getSplitPolicyApplied()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(expense.isSplitFallback()).isFalse();
        assertThat(expense.getCreatedAt()).isEqualTo(NOW);
        assertThat(expense.getUpdatedAt()).isEqualTo(NOW);
        assertThat(expense.getVersion()).isNull();
        // Orden por userId como en PostgreSQL: "1000..." antes que "f000...".
        assertThat(expense.getShares()).extracting(ExpenseShare::getUserId).containsExactly(BOB, ANA);
        assertThat(expense.getShares()).extracting(ExpenseShare::getAmount)
                .containsOnly(new BigDecimal("5.25"));
        assertThat(expense.getShares()).extracting(ExpenseShare::getWeight).containsOnly(new BigDecimal("1.00"));
        assertThat(expense.getShares()).extracting(ExpenseShare::getId).doesNotContainNull();
    }

    @Test
    void sharesAreReadOnly() {
        SharedExpense expense = groceries("10", equal("5", "5"));

        assertThatThrownBy(() -> expense.getShares().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fallbackWarningIsRemembered() {
        SplitResult fallback = new SplitResult(SplitPolicy.EQUAL,
                List.of(share(ANA, "5", "1"), share(BOB, "5", "1")),
                Set.of(SplitWarning.PROPORTIONAL_FALLBACK_NO_INCOME));

        SharedExpense expense = groceries("10", fallback);

        assertThat(expense.isSplitFallback()).isTrue();
        assertThat(expense.getSplitPolicyApplied()).isEqualTo(SplitPolicy.EQUAL);
    }

    @Test
    void fallbackMustBeAppliedAsEqual() {
        SplitResult inconsistent = new SplitResult(SplitPolicy.PROPORTIONAL, List.of(share(ANA, "10", "0")),
                Set.of(SplitWarning.PROPORTIONAL_FALLBACK_NO_INCOME));

        assertThatThrownBy(() -> groceries("10", inconsistent)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replaceSharesSubstitutesTheWholeSplit() {
        SharedExpense expense = groceries("10", equal("5", "5"));
        UUID carla = UUID.randomUUID();
        SplitResult proportional = new SplitResult(SplitPolicy.PROPORTIONAL,
                List.of(share(ANA, "7.50", "3000"), share(carla, "2.50", "1000")), Set.of());

        expense.replaceShares(proportional, NOW.plusSeconds(30));

        assertThat(expense.getSplitPolicyApplied()).isEqualTo(SplitPolicy.PROPORTIONAL);
        assertThat(expense.getShares()).hasSize(2)
                .extracting(ExpenseShare::getUserId).containsExactlyInAnyOrder(ANA, carla);
        assertThat(expense.getShares()).filteredOn(s -> s.getUserId().equals(ANA))
                .extracting(ExpenseShare::getWeight).containsExactly(new BigDecimal("3000.00"));
        assertThat(expense.getUpdatedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void replaceSharesRequiresSumEqualToAmount() {
        SharedExpense expense = groceries("10", equal("5", "5"));

        assertThatThrownBy(() -> expense.replaceShares(equal("5", "4.99"), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9.99");
        assertThat(expense.getShares()).extracting(ExpenseShare::getAmount)
                .containsOnly(new BigDecimal("5.00"));
        assertThat(expense.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void replaceSharesRejectsInvalidSplits() {
        SharedExpense expense = groceries("10", equal("5", "5"));
        Instant later = NOW.plusSeconds(1);

        assertThatThrownBy(() -> expense.replaceShares(new SplitResult(SplitPolicy.EQUAL, List.of(), Set.of()), later))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expense.replaceShares(split(share(ANA, "5", "1"), share(ANA, "5", "1")), later))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expense.replaceShares(split(share(ANA, "11", "1"), share(BOB, "-1", "1")), later))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expense.replaceShares(split(share(ANA, "5.001", "1"), share(BOB, "4.999", "1")),
                later)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expense.replaceShares(split(share(ANA, "5", "-1"), share(BOB, "5", "1")), later))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expense.replaceShares(null, later)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> expense.replaceShares(new SplitResult(null, List.of(share(ANA, "10", "1")), Set.of()),
                later)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void zeroShareIsAllowed() {
        SharedExpense expense = groceries("10", new SplitResult(SplitPolicy.PROPORTIONAL,
                List.of(share(ANA, "10", "2000"), share(BOB, "0", "0")), Set.of()));

        assertThat(expense.getShares()).extracting(ExpenseShare::getAmount)
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("10.00"));
    }

    @Test
    void changeDetailsKeepsShares() {
        SharedExpense expense = groceries("10", equal("5", "5"));
        List<ExpenseShare> before = List.copyOf(expense.getShares());

        expense.changeDetails("Luz enero", ExpenseCategory.ELECTRICITY, NOW.plusSeconds(10));

        assertThat(expense.getDescription()).isEqualTo("Luz enero");
        assertThat(expense.getCategory()).isEqualTo(ExpenseCategory.ELECTRICITY);
        assertThat(expense.getShares()).containsExactlyElementsOf(before);
        assertThat(expense.getUpdatedAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void changeAmountDatePayerDoesNotTouchSharesUntilReplaced() {
        SharedExpense expense = groceries("10", equal("5", "5"));

        expense.changeAmountDatePayer(new BigDecimal("12"), DATE.plusDays(1), BOB, NOW.plusSeconds(10));

        assertThat(expense.getAmount()).isEqualTo(new BigDecimal("12.00"));
        assertThat(expense.getExpenseDate()).isEqualTo(DATE.plusDays(1));
        assertThat(expense.getPaidBy()).isEqualTo(BOB);
        assertThat(expense.getShares()).extracting(ExpenseShare::getAmount).containsOnly(new BigDecimal("5.00"));

        expense.replaceShares(equal("6", "6"), NOW.plusSeconds(11));
        assertThat(expense.getShares()).extracting(ExpenseShare::getAmount).containsOnly(new BigDecimal("6.00"));
    }

    @Test
    void validatesScalarFields() {
        SplitResult split = equal("5", "5");
        assertThatThrownBy(() -> create("10", "eur", "Compra", ExpenseCategory.OTHER, split))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("10", null, "Compra", ExpenseCategory.OTHER, split))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("10", "EUR", " ", ExpenseCategory.OTHER, split))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("10", "EUR", null, ExpenseCategory.OTHER, split))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("10", "EUR", "x".repeat(201), ExpenseCategory.OTHER, split))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("10", "EUR", "Compra", null, split))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> create("0", "EUR", "Compra", ExpenseCategory.OTHER, split))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("-10", "EUR", "Compra", ExpenseCategory.OTHER, split))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("10.001", "EUR", "Compra", ExpenseCategory.OTHER, split))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create("1E+17", "EUR", "Compra", ExpenseCategory.OTHER, split))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(create("10", "EUR", "x".repeat(200), ExpenseCategory.OTHER, split).getDescription()).hasSize(200);
    }

    @Test
    void requiresIdsDateAndClock() {
        SplitResult split = equal("5", "5");
        BigDecimal ten = BigDecimal.TEN;
        ExpenseCategory other = ExpenseCategory.OTHER;
        assertThatThrownBy(() -> SharedExpense.create(null, ANA, ANA, ten, "EUR", "x", other, DATE, split, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SharedExpense.create(HOUSEHOLD, null, ANA, ten, "EUR", "x", other, DATE, split, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SharedExpense.create(HOUSEHOLD, ANA, null, ten, "EUR", "x", other, DATE, split, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SharedExpense.create(HOUSEHOLD, ANA, ANA, ten, "EUR", "x", other, null, split, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SharedExpense.create(HOUSEHOLD, ANA, ANA, ten, "EUR", "x", other, DATE, split, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityById() {
        SharedExpense a = groceries("10", equal("5", "5"));
        SharedExpense b = groceries("10", equal("5", "5"));
        ExpenseShare share = a.getShares().get(0);

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(a);
        assertThat(a.toString()).contains(a.getId().toString());
        assertThat(share).isEqualTo(share).isNotEqualTo(a.getShares().get(1)).hasSameHashCodeAs(share);
        assertThat(share.toString()).contains(share.getUserId().toString());
    }

    private static SharedExpense groceries(String amount, SplitResult split) {
        return create(amount, "EUR", "Compra", ExpenseCategory.GROCERIES, split);
    }

    private static SharedExpense create(String amount, String currency, String description, ExpenseCategory category,
            SplitResult split) {
        return SharedExpense.create(HOUSEHOLD, ANA, ANA, new BigDecimal(amount), currency, description, category, DATE,
                split, NOW);
    }

    private static SplitResult equal(String anaAmount, String bobAmount) {
        return split(share(ANA, anaAmount, "1"), share(BOB, bobAmount, "1"));
    }

    private static SplitResult split(ShareAllocation... shares) {
        return new SplitResult(SplitPolicy.EQUAL, List.of(shares), Set.of());
    }

    private static ShareAllocation share(UUID userId, String amount, String weight) {
        return new ShareAllocation(userId, new BigDecimal(amount), new BigDecimal(weight));
    }
}
