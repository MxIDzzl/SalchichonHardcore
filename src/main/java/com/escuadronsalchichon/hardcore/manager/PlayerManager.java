
package com.escuadronsalchichon.hardcore.manager;

import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import com.escuadronsalchichon.hardcore.player.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public class PlayerManager {

    private final JavaPlugin plugin;
    private final PlayerDataStore databaseManager;

    public PlayerManager(
            JavaPlugin plugin,
            PlayerDataStore databaseManager
    ) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    private ZoneId getTimeZone() {
        String timezone = plugin.getConfig().getString(
                "settings.timezone",
                "America/Mexico_City"
        );

        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            plugin.getLogger().warning(
                    "Zona horaria inválida: " + timezone
                            + ". Se utilizará America/Mexico_City."
            );

            return ZoneId.of("America/Mexico_City");
        }
    }

    public void resetExpiredDeaths() {
        LocalDate today = LocalDate.now(getTimeZone());
        databaseManager.resetDeathsBefore(today);
    }

    public PlayerData loadPlayer(Player player) {
        UUID uuid = player.getUniqueId();

        try {
            PlayerData playerData = databaseManager.getPlayer(uuid);

            if (playerData == null) {
                playerData = new PlayerData(
                        uuid,
                        player.getName(),
                        null
                );

                if (!databaseManager.savePlayer(playerData)) {
                    throw new IllegalStateException(
                            "No se pudo guardar el nuevo jugador."
                    );
                }

                plugin.getLogger().info(
                        "Jugador registrado: " + player.getName()
                );

            } else {
                playerData.setUsername(player.getName());

                if (!databaseManager.savePlayer(playerData)) {
                    throw new IllegalStateException(
                            "No se pudo actualizar el jugador."
                    );
                }
            }

            return playerData;

        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "Error al cargar los datos de " + player.getName()
            );

            exception.printStackTrace();

            return null;
        }
    }

    public boolean hasLife(UUID uuid) {
        PlayerData playerData = databaseManager.getPlayer(uuid);

        if (playerData == null) {
            return false;
        }

        LocalDate today = LocalDate.now(getTimeZone());

        LocalDate lastDeathDate = playerData.getLastDeathDate();

        return lastDeathDate == null || lastDeathDate.isBefore(today);
    }

    public boolean registerDeath(Player player) {
        try {
            UUID uuid = player.getUniqueId();

            PlayerData playerData = databaseManager.getPlayer(uuid);

            if (playerData == null) {
                plugin.getLogger().severe(
                        "No se pudo registrar la muerte: jugador inexistente "
                                + player.getName()
                );

                return false;
            }

            LocalDate today = LocalDate.now(getTimeZone());

            playerData.setLastDeathDate(today);
            playerData.setUsername(player.getName());

            if (!databaseManager.savePlayer(playerData)) {
                throw new IllegalStateException(
                        "No se pudo guardar la fecha de muerte."
                );
            }

            plugin.getLogger().info(
                    player.getName() + " ha perdido su vida diaria."
            );

            return true;

        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "Error al registrar la muerte de " + player.getName()
            );

            exception.printStackTrace();

            return false;
        }
    }
}
