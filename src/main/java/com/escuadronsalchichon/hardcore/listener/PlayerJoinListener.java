package com.escuadronsalchichon.hardcore.listener;

import org.bukkit.ChatColor;
import com.escuadronsalchichon.hardcore.manager.BossBarManager;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerManager playerManager;
    private final BossBarManager bossBarManager;

    public PlayerJoinListener(
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
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        try {
            if (playerManager.loadPlayer(player) == null) {
                player.kickPlayer(message(
                        "messages.data-error",
                        "&cNo se pudieron cargar tus datos."
                ));
                return;
            }

            if (!playerManager.hasLife(player.getUniqueId())) {
                player.kickPlayer(
                        message("messages.no-life", "&c¡Has perdido tu vida diaria!")
                                + "\n"
                                + message("messages.return-tomorrow", "&7Podrás regresar mañana a las 00:00.")
                );
                return;
            }

            bossBarManager.showStatus(player, true);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "Error al procesar la conexión de " + player.getName()
            );
            exception.printStackTrace();
            player.kickPlayer(message(
                    "messages.data-error",
                    "&cOcurrió un error al consultar tus datos."
            ));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        bossBarManager.removeStatus(event.getPlayer());
    }
}
