package com.hogar.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Money: validación y conversión exacta de importes")
class MoneyTest {

    @Test
    @DisplayName("un importe positivo se normaliza a escala 2")
    void positiveAmountIsNormalizedToScale2() {
        assertThat(Money.requirePositiveAmount(new BigDecimal("10"))).isEqualTo(new BigDecimal("10.00"));
        assertThat(Money.requirePositiveAmount(new BigDecimal("0.5"))).isEqualTo(new BigDecimal("0.50"));
        assertThat(Money.requirePositiveAmount(new BigDecimal("0.01"))).isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    @DisplayName("los ceros a la derecha no cuentan como decimales")
    void trailingZerosAreNotFractionsOfCent() {
        assertThat(Money.requirePositiveAmount(new BigDecimal("10.000"))).isEqualTo(new BigDecimal("10.00"));
        assertThat(Money.requirePositiveAmount(new BigDecimal("1E+2"))).isEqualTo(new BigDecimal("100.00"));
    }

    @ParameterizedTest(name = "rechaza {0}")
    @ValueSource(strings = {"0", "0.00", "-0.01", "-5"})
    @DisplayName("un importe positivo no puede ser 0 ni negativo")
    void positiveAmountRejectsZeroAndNegative(String value) {
        assertThatThrownBy(() -> Money.requirePositiveAmount(new BigDecimal(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no se admiten fracciones de céntimo (escala 3)")
    void rejectsFractionsOfCent() {
        assertThatThrownBy(() -> Money.requirePositiveAmount(new BigDecimal("1.005")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimales");
        assertThatThrownBy(() -> Money.toCents(new BigDecimal("0.001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null se rechaza con IllegalArgumentException")
    void rejectsNull() {
        assertThatThrownBy(() -> Money.requirePositiveAmount(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.requireNonNegativeAmount(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.toCents(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un importe no negativo admite 0 pero no negativos")
    void nonNegativeAmountAcceptsZero() {
        assertThat(Money.requireNonNegativeAmount(BigDecimal.ZERO)).isEqualTo(new BigDecimal("0.00"));
        assertThat(Money.requireNonNegativeAmount(new BigDecimal("1500"))).isEqualTo(new BigDecimal("1500.00"));
        assertThatThrownBy(() -> Money.requireNonNegativeAmount(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativo");
    }

    @Test
    @DisplayName("toCents y fromCents son inversos y exactos")
    void centsRoundTrip() {
        assertThat(Money.toCents(new BigDecimal("10.00"))).isEqualTo(BigInteger.valueOf(1000));
        assertThat(Money.toCents(new BigDecimal("0.1"))).isEqualTo(BigInteger.TEN);
        assertThat(Money.toCents(new BigDecimal("-3.33"))).isEqualTo(BigInteger.valueOf(-333));
        assertThat(Money.fromCents(333)).isEqualTo(new BigDecimal("3.33"));
        assertThat(Money.fromCents(BigInteger.valueOf(100_000_000_00L))).isEqualTo(new BigDecimal("100000000.00"));
        BigDecimal huge = new BigDecimal("99999999999999999.99");
        assertThat(Money.fromCents(Money.toCents(huge))).isEqualTo(huge);
    }
}
