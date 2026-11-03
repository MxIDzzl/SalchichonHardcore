
package com.escuadronsalchichon.hardcore.manager;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BossBarManager {

    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    public void showStatus(Player player, boolean hasLife) {

        UUID uuid = player.getUniqueId();

        removeStatus(player);

        String title;
        BarColor color;

        if (hasLife) {
            title = "§aVIDA DISPONIBLE";
            color = BarColor.GREEN;
        } else {
            title = "§cSIN VIDAS";
            color = BarColor.RED;
        }

        BossBar bossBar = Bukkit.createBossBar(
                title,
                color,
                BarStyle.SOLID
        );

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
