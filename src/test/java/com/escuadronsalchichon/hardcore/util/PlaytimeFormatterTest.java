package com.escuadronsalchichon.hardcore.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaytimeFormatterTest {
    @Test
    void formatsMinutesAndHoursFromTicks() {
        assertEquals("0m", PlaytimeFormatter.format(0));
        assertEquals("1m", PlaytimeFormatter.format(1_200));
        assertEquals("1h 1m", PlaytimeFormatter.format(73_200));
        assertEquals("1d 2h", PlaytimeFormatter.format(1_872_000));
    }

    @Test
    void clampsNegativeTicksToZero() {
        assertEquals("0m", PlaytimeFormatter.format(-20_000));
    }
}
