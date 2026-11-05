package com.escuadronsalchichon.hardcore.vault;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.entity.Player;

public final class VaultMenuListener implements Listener {
    private final SalchichonHardcore plugin;

    public VaultMenuListener(SalchichonHardcore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof VaultMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;

        if (event.getRawSlot() == 45 && holder.hasPrevious()) {
            openPage(player, holder.page() - 1);
        } else if (event.getRawSlot() == 53 && holder.hasNext()) {
            openPage(player, holder.page() + 1);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof VaultMenuHolder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    private void openPage(Player player, int page) {
        player.closeInventory();
        Bukkit.dispatchCommand(player, "value " + (page + 1));
    }
}
