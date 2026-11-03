
package com.escuadronsalchichon.hardcore.listener;

import com.escuadronsalchichon.hardcore.manager.BossBarManager;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
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

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {

        Player player = event.getEntity();

        boolean registered = playerManager.registerDeath(player);

        if (!registered) {
            player.kickPlayer(
                    "§cNo pudimos registrar tu muerte.\n"
                            + "§7Contacta a un administrador."
            );
            return;
        }

        // Mostrar la BossBar roja.
        bossBarManager.showStatus(player, false);

        // Expulsar después de 3 segundos.
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    // Comprobar nuevamente si todavía no tiene vida.
                    if (playerManager.hasLife(player.getUniqueId())) {
                        bossBarManager.showStatus(player, true);
                        return;
                    }

                    player.kickPlayer(
                            "§c¡Has perdido tu vida diaria!\n"
                                    + "§7Podrás regresar mañana a las 00:00."
                    );
                },
                60L
        );
    }
}
