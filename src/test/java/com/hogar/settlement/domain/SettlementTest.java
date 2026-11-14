package com.hogar.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SettlementTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 3, 5);
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void createsWithAllDataAsNew() {
        Settlement settlement = Settlement.create(HOUSEHOLD, ANA, BOB, new BigDecimal("25.5"), "EUR", DATE,
                "  Bizum de la luz  ", OWNER, NOW);

        assertThat(settlement.getId()).isNotNull();
        assertThat(settlement.isNew()).isTrue();
        assertThat(settlement.getHouseholdId()).isEqualTo(HOUSEHOLD);
        assertThat(settlement.getFromUserId()).isEqualTo(ANA);
        assertThat(settlement.getToUserId()).isEqualTo(BOB);
        assertThat(settlement.getAmount()).isEqualTo("25.50").hasScaleOf(2);
        assertThat(settlement.getCurrency()).isEqualTo("EUR");
        assertThat(settlement.getDate()).isEqualTo(DATE);
        assertThat(settlement.getNote()).isEqualTo("Bizum de la luz");
        assertThat(settlement.getCreatedBy()).isEqualTo(OWNER);
        assertThat(settlement.getCreatedAt()).isEqualTo(NOW);
        assertThat(settlement.toString()).contains(settlement.getId().toString());
    }

    @Test
    void eachSettlementGetsItsOwnId() {
        assertThat(valid().getId()).isNotEqualTo(valid().getId());
    }

    @Test
    void isNoLongerNewOncePersistedOrLoaded() {
        Settlement settlement = valid();

        settlement.markNotNew();

        assertThat(settlement.isNew()).isFalse();
    }

    @Test
    void involvesOnlyBothParties() {
        Settlement settlement = valid();

        assertThat(settlement.involves(ANA)).isTrue();
        assertThat(settlement.involves(BOB)).isTrue();
        assertThat(settlement.involves(OWNER)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "0.01", "10.5", "10.50", "10.500", "1E+2", "999999999.99"})
    void acceptsPositiveAmountsWithAtMostTwoDecimalsNormalizedToScaleTwo(String amount) {
        Settlement settlement = withAmount(new BigDecimal(amount));

        assertThat(settlement.getAmount()).isEqualByComparingTo(amount).hasScaleOf(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01", "0.001", "1.005", "1E+17"})
    void rejectsZeroNegativeTooPreciseOrTooLargeAmounts(String amount) {
        assertThatThrownBy(() -> withAmount(new BigDecimal(amount)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void largestAmountThatFitsNumeric19x2IsAccepted() {
        assertThat(withAmount(new BigDecimal("99999999999999999.99")).getAmount())
                .isEqualTo("99999999999999999.99");
    }

    @Test
    void rejectsSamePayerAndReceiver() {
        assertThatThrownBy(() -> Settlement.create(HOUSEHOLD, ANA, ANA, BigDecimal.TEN, "EUR", DATE, null, ANA, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"eur", "EURO", "EU", "E1R", " EUR"})
    void rejectsInvalidCurrency(String currency) {
        assertThatThrownBy(() -> Settlement.create(HOUSEHOLD, ANA, BOB, BigDecimal.TEN, currency, DATE, null, ANA,
                NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsDatesAtTheBoundsOfTheRange() {
        assertThat(withDate(Settlement.MIN_DATE).getDate()).isEqualTo(LocalDate.of(1, 1, 1));
        assertThat(withDate(Settlement.MAX_DATE).getDate()).isEqualTo(LocalDate.of(9999, 12, 31));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-12-31", "-0100-03-31", "+10000-01-01"})
    void rejectsDatesOutsideYearsOneTo9999(String date) {
        assertThatThrownBy(() -> withDate(LocalDate.parse(date)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n", " 　"})
    void blankNoteIsStoredAsNull(String note) {
        assertThat(withNote(note).getNote()).isNull();
    }

    @Test
    void noteOfMaxLengthInCodePointsIsAcceptedAfterStripping() {
        String emojis = "😀".repeat(Settlement.MAX_NOTE_LENGTH);

        assertThat(withNote("  " + emojis + "\n").getNote()).isEqualTo(emojis);
        assertThat(withNote("a".repeat(Settlement.MAX_NOTE_LENGTH)).getNote()).hasSize(Settlement.MAX_NOTE_LENGTH);
    }

    @Test
    void rejectsNoteLongerThanMax() {
        assertThatThrownBy(() -> withNote("a".repeat(Settlement.MAX_NOTE_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withNote("😀".repeat(Settlement.MAX_NOTE_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiredReferencesMustNotBeNull() {
        BigDecimal ten = BigDecimal.TEN;
        assertThatNullPointerException().isThrownBy(
                () -> Settlement.create(null, ANA, BOB, ten, "EUR", DATE, null, ANA, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> Settlement.create(HOUSEHOLD, null, BOB, ten, "EUR", DATE, null, ANA, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> Settlement.create(HOUSEHOLD, ANA, null, ten, "EUR", DATE, null, ANA, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> Settlement.create(HOUSEHOLD, ANA, BOB, null, "EUR", DATE, null, ANA, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> Settlement.create(HOUSEHOLD, ANA, BOB, ten, "EUR", null, null, ANA, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> Settlement.create(HOUSEHOLD, ANA, BOB, ten, "EUR", DATE, null, null, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> Settlement.create(HOUSEHOLD, ANA, BOB, ten, "EUR", DATE, null, ANA, null));
    }

    @Test
    void equalityIsById() {
        Settlement settlement = valid();

        assertThat(settlement).isEqualTo(settlement).isNotEqualTo(valid()).isNotEqualTo("x");
        assertThat(settlement.hashCode()).isEqualTo(settlement.getId().hashCode());
    }

    private static Settlement valid() {
        return withNote(null);
    }

    private static Settlement withAmount(BigDecimal amount) {
        return Settlement.create(HOUSEHOLD, ANA, BOB, amount, "EUR", DATE, null, ANA, NOW);
    }

    private static Settlement withDate(LocalDate date) {
        return Settlement.create(HOUSEHOLD, ANA, BOB, BigDecimal.TEN, "EUR", date, null, ANA, NOW);
    }

    private static Settlement withNote(String note) {
        return Settlement.create(HOUSEHOLD, ANA, BOB, BigDecimal.TEN, "EUR", DATE, note, ANA, NOW);
    }
}
