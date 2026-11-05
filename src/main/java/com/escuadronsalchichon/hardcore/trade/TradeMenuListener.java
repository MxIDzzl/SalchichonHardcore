package com.escuadronsalchichon.hardcore.trade;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class TradeMenuListener implements Listener {
    private final TradeService trades;

    public TradeMenuListener(TradeService trades) {
        this.trades = trades;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TradeMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.viewer().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        switch (event.getRawSlot()) {
            case 45 -> {
                if (holder.hasPrevious()) trades.openMenu(player, holder.page() - 1);
            }
            case 49 -> {
                player.closeInventory();
                trades.confirm(player);
            }
            case 53 -> {
                if (holder.hasNext()) trades.openMenu(player, holder.page() + 1);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TradeMenuHolder) {
            event.setCancelled(true);
        }
    }
}
