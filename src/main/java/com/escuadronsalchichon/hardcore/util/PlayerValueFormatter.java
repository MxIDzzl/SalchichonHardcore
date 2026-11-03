package com.escuadronsalchichon.hardcore.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Compact, locale-independent formatting for the global Player Value display. */
public final class PlayerValueFormatter {
    private static final String[] SUFFIXES = {"", "k", "m", "b", "t", "q"};

    private PlayerValueFormatter() {}

    public static String compact(long value) {
        if (value > -1000L && value < 1000L) return Long.toString(value);
        BigDecimal number = BigDecimal.valueOf(value);
        int tier = 0;
        BigDecimal thousand = BigDecimal.valueOf(1000);
        while (number.abs().compareTo(thousand) >= 0 && tier < SUFFIXES.length - 1) {
            number = number.divide(thousand, 3, RoundingMode.HALF_UP);
            tier++;
        }
        BigDecimal rounded = number.setScale(number.abs().compareTo(BigDecimal.TEN) < 0 ? 1 : 0,
                RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.toPlainString() + SUFFIXES[tier];
    }
}
