package com.escuadronsalchichon.hardcore.util;

/** Consistent, tick-based playtime formatting for commands and placeholders. */
public final class PlaytimeFormatter {
    private PlaytimeFormatter() {}

    public static String format(long ticks) {
        long totalMinutes = Math.max(0L, ticks) / 20L / 60L;
        long days = totalMinutes / 1440L;
        long hours = (totalMinutes % 1440L) / 60L;
        long minutes = totalMinutes % 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
