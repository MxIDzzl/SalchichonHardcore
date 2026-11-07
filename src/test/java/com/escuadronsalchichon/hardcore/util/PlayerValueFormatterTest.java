package com.escuadronsalchichon.hardcore.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerValueFormatterTest {
    @Test void keepsSmallValuesExact() {
        assertEquals("0", PlayerValueFormatter.compact(0));
        assertEquals("950", PlayerValueFormatter.compact(950));
        assertEquals("-950", PlayerValueFormatter.compact(-950));
    }

    @Test void formatsThousandsAndMillions() {
        assertEquals("1k", PlayerValueFormatter.compact(1_000));
        assertEquals("1.2k", PlayerValueFormatter.compact(1_200));
        assertEquals("3.5m", PlayerValueFormatter.compact(3_500_000));
        assertEquals("3.5b", PlayerValueFormatter.compact(3_500_000_000L));
    }

    @Test void supportsNegativeAndVeryLargeValues() {
        assertEquals("-1.2k", PlayerValueFormatter.compact(-1_200));
        assertEquals("9223q", PlayerValueFormatter.compact(Long.MAX_VALUE));
    }
}
