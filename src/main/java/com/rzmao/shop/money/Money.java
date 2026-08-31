package com.rzmao.shop.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class Money {
    private static final Pattern DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?");

    private Money() {
    }

    public static long parse(String value, boolean allowZero) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException("金额必须是最多两位小数的非负十进制数: " + value);
        }
        long minor;
        try {
            minor = new BigDecimal(value).movePointRight(2).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("金额超出 64 位范围: " + value, ex);
        }
        if (minor < 0 || (!allowZero && minor == 0)) {
            throw new IllegalArgumentException("金额必须大于 0: " + value);
        }
        return minor;
    }

    public static String format(long minor) {
        return BigDecimal.valueOf(minor, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    public static long multiply(long unitPrice, int count) {
        if (unitPrice < 0 || count < 0) {
            throw new IllegalArgumentException("金额和数量不能为负数");
        }
        return Math.multiplyExact(unitPrice, (long) count);
    }

    public static long fractionOf(long amount, BigDecimal ratio) {
        if (amount < 0) {
            throw new IllegalArgumentException("金额不能为负数");
        }
        Objects.requireNonNull(ratio, "ratio");
        if (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("比例必须在 0 到 1 之间");
        }
        return BigDecimal.valueOf(amount).multiply(ratio)
                .setScale(0, RoundingMode.DOWN).longValueExact();
    }

    public static String normalize(String value) {
        return format(parse(value.toLowerCase(Locale.ROOT), true));
    }
}
