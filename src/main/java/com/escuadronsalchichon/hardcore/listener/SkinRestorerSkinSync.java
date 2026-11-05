package com.escuadronsalchichon.hardcore.listener;

import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.SkinsRestorer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Copies only the public texture hash from SkinsRestorer into the server's
 * read-only public profile data. Network/storage work never runs on the
 * Bukkit main thread.
 */
public final class SkinRestorerSkinSync implements Listener {
    private final JavaPlugin plugin;
    private final PlayerDataStore store;
    private final SkinsRestorer skinsRestorer;

    public SkinRestorerSkinSync(JavaPlugin plugin, PlayerDataStore store, SkinsRestorer skinsRestorer) {
        this.plugin = plugin;
        this.store = store;
        this.skinsRestorer = skinsRestorer;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var property = skinsRestorer.getPlayerStorage().getSkinForPlayer(playerUuid, playerName);
                if (property.isEmpty()) {
                    store.updatePlayerSkin(playerUuid, null);
                    return;
                }

                String textureHash = PropertyUtils.getSkinTextureHash(property.get());
                if (textureHash == null || !textureHash.matches("(?i)[0-9a-f]{32,64}")) {
                    plugin.getLogger().warning("SkinsRestorer devolvió un identificador de textura inválido para " + playerName + ".");
                    return;
                }
                if (!store.updatePlayerSkin(playerUuid, textureHash)) {
                    plugin.getLogger().warning("No se pudo sincronizar la skin pública de " + playerName + ".");
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING,
                        "No se pudo consultar la skin de " + playerName + " en SkinsRestorer.", exception);
            }
        });
    }
}
