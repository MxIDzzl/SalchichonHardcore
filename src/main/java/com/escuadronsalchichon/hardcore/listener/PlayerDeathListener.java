package com.escuadronsalchichon.hardcore.listener;

import com.escuadronsalchichon.hardcore.manager.BossBarManager;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerDeathListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerManager playerManager;
    private final BossBarManager bossBarManager;

    public PlayerDeathListener(
            JavaPlugin plugin,
            PlayerManager playerManager,
            BossBarManager bossBarManager
    ) {
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.bossBarManager = bossBarManager;
    }

    private String message(String path, String fallback) {
        return ChatColor.translateAlternateColorCodes(
                '&',
                plugin.getConfig().getString(path, fallback)
        );
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (!playerManager.registerDeath(player)) {
            player.kickPlayer(message(
                    "messages.data-error",
                    "&cNo se pudo registrar tu muerte. Contacta a un administrador."
            ));
            return;
        }

        bossBarManager.showStatus(player, false);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            try {
                if (playerManager.hasLife(player.getUniqueId())) {
                    bossBarManager.showStatus(player, true);
                    return;
                }

                player.kickPlayer(
                        message("messages.no-life", "&c¡Has perdido tu vida diaria!")
                                + "\n"
                                + message("messages.return-tomorrow", "&7Podrás regresar mañana a las 00:00.")
                );
            } catch (RuntimeException exception) {
                plugin.getLogger().severe(
                        "No se pudo comprobar el estado de vida de " + player.getName()
                );
                exception.printStackTrace();
                player.kickPlayer(message(
                        "messages.data-error",
                        "&cNo se pudieron cargar tus datos."
                ));
            }
        }, 60L);
    }
}
