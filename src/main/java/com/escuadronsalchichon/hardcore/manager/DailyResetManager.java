package com.escuadronsalchichon.hardcore.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class DailyResetManager {

    private static final ZoneId TIME_ZONE =
            ZoneId.of("America/Mexico_City");

    private final JavaPlugin plugin;
    private final PlayerManager playerManager;
    private final BossBarManager bossBarManager;

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

        // Recuperar las vidas expiradas al iniciar el servidor.
        try {
            playerManager.resetExpiredDeaths();
            updateOnlinePlayers();

        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "No se pudieron recuperar las vidas expiradas."
            );
            exception.printStackTrace();
        }

        scheduleNextReset();
    }

    private void scheduleNextReset() {

        LocalDateTime now = LocalDateTime.now(TIME_ZONE);

        LocalDateTime nextReset = now.toLocalDate()
                .plusDays(1)
                .atStartOfDay();

        long ticksUntilReset = Math.max(
                1L,
                Duration.between(now, nextReset).toMillis() / 50
        );

        Bukkit.getScheduler().runTaskLater(
                plugin,
                this::performReset,
                ticksUntilReset
        );

        plugin.getLogger().info(
                "Próximo reinicio diario: " + nextReset
        );
    }

    private void performReset() {

        try {
            playerManager.resetExpiredDeaths();
            updateOnlinePlayers();

            plugin.getLogger().info(
                    "Reinicio diario de vidas completado."
            );

        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "Error durante el reinicio diario de vidas."
            );
            exception.printStackTrace();

        } finally {
            // Programar el siguiente reinicio aunque ocurra un error.
            scheduleNextReset();
        }
    }

    private void updateOnlinePlayers() {

        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean hasLife = playerManager.hasLife(
                    player.getUniqueId()
            );

            bossBarManager.showStatus(player, hasLife);
        }
    }
}