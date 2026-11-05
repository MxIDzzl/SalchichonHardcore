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
        int slot = event.getRawSlot();
        if (slot == 45 && holder.hasPrevious()) {
            trades.openMenu(player, holder.page() - 1);
            return;
        }
        if (slot == 53 && holder.hasNext()) {
            trades.openMenu(player, holder.page() + 1);
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            trades.confirm(player, holder.session().revision());
            return;
        }
        TradeEscrowRecord selected = null;
        if (slot >= 0 && slot < TradeMenu.PAGE_SIZE) {
            var side = holder.items().stream()
                    .filter(item -> item.ownerUuid().equals(holder.session().playerA()))
                    .filter(item -> !"RETURNED".equals(item.status())).toList();
            int index = holder.page() * TradeMenu.PAGE_SIZE + slot;
            if (index < side.size()) selected = side.get(index);
        } else if (slot >= 27 && slot < 27 + TradeMenu.PAGE_SIZE) {
            var side = holder.items().stream()
                    .filter(item -> item.ownerUuid().equals(holder.session().playerB()))
                    .filter(item -> !"RETURNED".equals(item.status())).toList();
            int index = holder.page() * TradeMenu.PAGE_SIZE + slot - 27;
            if (index < side.size()) selected = side.get(index);
        }
        if (selected != null) {
            if (!selected.ownerUuid().equals(player.getUniqueId())) {
                player.sendMessage("§7Solo puedes retirar tus propios objetos de la oferta.");
                return;
            }
            if (!"ESCROWED".equals(selected.status())) {
                player.sendMessage("§eEse objeto está procesándose y no se puede retirar todavía.");
                return;
            }
            player.closeInventory();
            trades.returnOffer(player, selected.operationId(), holder.session().revision());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TradeMenuHolder) {
            event.setCancelled(true);
        }
    }
}
