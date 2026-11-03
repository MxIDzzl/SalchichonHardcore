package com.escuadronsalchichon.hardcore.manager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class BossBarManager {

    private final JavaPlugin plugin;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    public BossBarManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void showStatus(Player player, boolean hasLife) {
        UUID uuid = player.getUniqueId();
        removeStatus(player);

        String path = hasLife ? "bossbar.available" : "bossbar.unavailable";
        String defaultText = hasLife ? "&aVIDA DISPONIBLE" : "&cSIN VIDAS";
        String defaultColor = hasLife ? "GREEN" : "RED";

        String configuredText = plugin.getConfig().getString(path + ".text", defaultText);
        String configuredColor = plugin.getConfig().getString(path + ".color", defaultColor);
        String title = ChatColor.translateAlternateColorCodes('&', configuredText);
        BarColor color;

        try {
            color = BarColor.valueOf(configuredColor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            plugin.getLogger().warning(
                    "Color de BossBar inválido en " + path + ".color: " + configuredColor
            );
            color = hasLife ? BarColor.GREEN : BarColor.RED;
        }

        BossBar bossBar = Bukkit.createBossBar(title, color, BarStyle.SOLID);
        bossBar.setProgress(1.0);
        bossBar.addPlayer(player);
        bossBar.setVisible(true);
        bossBars.put(uuid, bossBar);
    }

    public void removeStatus(Player player) {
        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            bossBar.removeAll();
        }
    }

    public void removeAll() {
        for (BossBar bossBar : bossBars.values()) {
            bossBar.removeAll();
        }
        bossBars.clear();
    }
}
