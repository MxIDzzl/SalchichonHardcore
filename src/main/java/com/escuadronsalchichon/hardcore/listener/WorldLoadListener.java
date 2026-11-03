
package com.escuadronsalchichon.hardcore.listener;

import com.escuadronsalchichon.hardcore.manager.WorldRuleManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

public class WorldLoadListener implements Listener {

    private final WorldRuleManager worldRuleManager;

    public WorldLoadListener(WorldRuleManager worldRuleManager) {
        this.worldRuleManager = worldRuleManager;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        worldRuleManager.applyRules(event.getWorld());
    }
}
