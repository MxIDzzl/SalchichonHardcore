package com.escuadronsalchichon.hardcore.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

public final class DifficultyProgression {
    private DifficultyProgression() {}

    public static long seasonDay(JavaPlugin plugin) {
        try {
            LocalDate start = LocalDate.parse(plugin.getConfig().getString("season.start-date", ""));
            ZoneId zone = ZoneId.of(plugin.getConfig().getString(
                    "settings.timezone", "America/Mexico_City"));
            long days = ChronoUnit.DAYS.between(start, LocalDate.now(zone));
            return days < 0 ? 0 : days + 1;
        } catch (RuntimeException invalidConfiguration) {
            return 0;
        }
    }

    public static String currentDifficulty(JavaPlugin plugin) {
        String base = plugin.getConfig().getString("season.difficulty", "—");
        long day = seasonDay(plugin);
        if (day == 0) return base;

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("season.difficulty-stages");
        if (section == null) return base;
        TreeMap<Long, String> stages = new TreeMap<>();
        for (String key : section.getKeys(false)) {
            try {
                long threshold = Long.parseLong(key);
                String value = section.getString(key, "").trim();
                if (threshold > 0 && !value.isBlank()) stages.put(threshold, value);
            } catch (NumberFormatException ignored) {
                // Ignore invalid optional stage keys.
            }
        }
        Map.Entry<Long, String> active = stages.floorEntry(day);
        return active == null ? base : active.getValue();
    }
}
