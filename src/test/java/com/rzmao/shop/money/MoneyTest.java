package com.rzmao.shop.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @Test
    void parsesAndFormatsFixedPointAmounts() {
        assertThat(Money.parse("0.01", false)).isEqualTo(1);
        assertThat(Money.parse("1", false)).isEqualTo(100);
        assertThat(Money.parse("123.40", false)).isEqualTo(12_340);
        assertThat(Money.format(12_340)).isEqualTo("123.40");
        assertThat(Money.format(-100)).isEqualTo("-1.00");
    }

    @Test
    void rejectsAmbiguousOrOverPreciseInput() {
        assertThatThrownBy(() -> Money.parse("1.001", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("1e3", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("01.00", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("-1", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("0", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsMultiplicationOverflow() {
        assertThat(Money.multiply(125, 8)).isEqualTo(1_000);
        assertThatThrownBy(() -> Money.multiply(Long.MAX_VALUE, 2)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void calculatesRatioUsingMinorUnitsAndRoundsDown() {
        assertThat(Money.fractionOf(12_345, new BigDecimal("0.10"))).isEqualTo(1_234);
        assertThat(Money.fractionOf(1, new BigDecimal("0.10"))).isZero();
        assertThat(Money.fractionOf(Long.MAX_VALUE, BigDecimal.ONE)).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> Money.fractionOf(100, new BigDecimal("1.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
