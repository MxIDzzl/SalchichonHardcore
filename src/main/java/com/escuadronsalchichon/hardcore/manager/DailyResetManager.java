package com.escuadronsalchichon.hardcore.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;

public class DailyResetManager {

    private final JavaPlugin plugin;
    private final PlayerManager playerManager;
    private final BossBarManager bossBarManager;
    private BukkitTask resetTask;

    public DailyResetManager(
            JavaPlugin plugin,
            PlayerManager playerManager,
            BossBarManager bossBarManager
    ) {
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.bossBarManager = bossBarManager;
    }

    public void start() {
        try {
            playerManager.resetExpiredDeaths();
            refreshOnlinePlayers();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("No se pudieron recuperar las vidas expiradas.");
            exception.printStackTrace();
        }
        scheduleNextReset();
    }

    public void cancel() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
    }

    public void reschedule() {
        cancel();
        scheduleNextReset();
        refreshOnlinePlayers();
    }

    private ZoneId getTimeZone() {
        String configuredZone = plugin.getConfig().getString(
                "settings.timezone",
                "America/Mexico_City"
        );
        try {
            return ZoneId.of(configuredZone);
        } catch (DateTimeException exception) {
            plugin.getLogger().warning(
                    "Zona horaria inválida: " + configuredZone
                            + ". Se utilizará America/Mexico_City."
            );
            return ZoneId.of("America/Mexico_City");
        }
    }

    private LocalTime getResetTime() {
        String configuredTime = plugin.getConfig().getString(
                "settings.daily-reset",
                "00:00"
        );
        try {
            return LocalTime.parse(configuredTime);
        } catch (DateTimeException exception) {
            plugin.getLogger().warning(
                    "Hora de reinicio inválida: " + configuredTime
                            + ". Se utilizará 00:00."
            );
            return LocalTime.MIDNIGHT;
        }
    }

    private void scheduleNextReset() {
        ZonedDateTime now = ZonedDateTime.now(getTimeZone());
        ZonedDateTime nextReset = now.toLocalDate()
                .atTime(getResetTime())
                .atZone(getTimeZone());

        if (!nextReset.isAfter(now)) {
            nextReset = nextReset.plusDays(1);
        }

        long ticksUntilReset = Math.max(
                1L,
                Duration.between(now.toInstant(), nextReset.toInstant()).toMillis() / 50L
        );

        resetTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                this::performReset,
                ticksUntilReset
        );

        plugin.getLogger().info("Próximo reinicio diario: " + nextReset);
    }

    private void performReset() {
        try {
            playerManager.resetExpiredDeaths();
            refreshOnlinePlayers();
            plugin.getLogger().info("Reinicio diario de vidas completado.");
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Error durante el reinicio diario de vidas.");
            exception.printStackTrace();
        } finally {
            scheduleNextReset();
        }
    }

    public void refreshOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean hasLife = playerManager.hasLife(player.getUniqueId());
            bossBarManager.showStatus(player, hasLife);
        }
    }
}
