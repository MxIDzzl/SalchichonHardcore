
package com.escuadronsalchichon.hardcore.listener;

import com.escuadronsalchichon.hardcore.manager.BossBarManager;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinListener implements Listener {

    private final PlayerManager playerManager;
    private final BossBarManager bossBarManager;

    public PlayerJoinListener(
            PlayerManager playerManager,
            BossBarManager bossBarManager
    ) {
        this.playerManager = playerManager;
        this.bossBarManager = bossBarManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {

        Player player = event.getPlayer();

        try {
            // Cargar o registrar los datos del jugador.
            if (playerManager.loadPlayer(player) == null) {
                player.kickPlayer(
                        "§cNo se pudieron cargar tus datos.\n"
                                + "§7Contacta a un administrador."
                );
                return;
            }

            // Comprobar si tiene una vida disponible.
            if (!playerManager.hasLife(player.getUniqueId())) {
                player.kickPlayer(
                        "§c¡Te has quedado sin vidas por hoy!\n"
                                + "§7Podrás regresar mañana a las 00:00."
                );
                return;
            }

            // Mostrar la BossBar individual.
            bossBarManager.showStatus(player, true);

        } catch (RuntimeException exception) {

            player.kickPlayer(
                    "§cOcurrió un error al consultar tus datos.\n"
                            + "§7Intenta nuevamente más tarde."
            );

            exception.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        bossBarManager.removeStatus(event.getPlayer());
    }
}
