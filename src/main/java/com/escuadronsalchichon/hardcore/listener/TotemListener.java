package com.escuadronsalchichon.hardcore.listener;

import com.escuadronsalchichon.hardcore.database.DatabaseManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;

public class TotemListener implements Listener {
    private final DatabaseManager databaseManager;

    public TotemListener(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            databaseManager.incrementTotems(player.getUniqueId());
        }
    }
}