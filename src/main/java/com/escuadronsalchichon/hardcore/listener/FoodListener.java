package com.escuadronsalchichon.hardcore.listener;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public class FoodListener implements Listener {

    @EventHandler
    public void onFoodConsume(PlayerItemConsumeEvent event) {

        Material item = event.getItem().getType();

        if (item == Material.GOLDEN_APPLE
                || item == Material.ENCHANTED_GOLDEN_APPLE) {
            return;
        }

        if (item.isEdible()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCakeEat(PlayerInteractEvent event) {

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getClickedBlock() == null) {
            return;
        }

        if (event.getClickedBlock().getType() == Material.CAKE) {
            event.setCancelled(true);
        }
    }

}
