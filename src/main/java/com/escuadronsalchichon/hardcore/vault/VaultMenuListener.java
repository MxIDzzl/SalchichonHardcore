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
    private final VaultWithdrawalService withdrawals;
    private final VaultInventoryService deposits;

    public VaultMenuListener(SalchichonHardcore plugin, VaultWithdrawalService withdrawals,
                             VaultInventoryService deposits) {
        this.plugin = plugin;
        this.withdrawals = withdrawals;
        this.deposits = deposits;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof VaultDepositMenuHolder depositHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (!player.getUniqueId().equals(depositHolder.playerUuid())) {
                player.closeInventory();
                return;
            }
            if (event.getClickedInventory() != event.getView().getTopInventory()) return;
            int slot = event.getRawSlot();
            if (slot >= 0 && slot < depositHolder.candidates().size()) {
                var candidate = depositHolder.candidates().get(slot);
                player.closeInventory();
                deposits.deposit(player, candidate);
            }
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof VaultMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.getUniqueId().equals(holder.playerUuid())) {
            player.closeInventory();
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;

        int slot = event.getRawSlot();
        if (slot == 45 && holder.hasPrevious()) {
            openPage(player, holder.page() - 1);
        } else if (slot == 53 && holder.hasNext()) {
            openPage(player, holder.page() + 1);
        } else if (slot >= 0 && slot < holder.items().size()) {
            var item = holder.items().get(slot);
            player.closeInventory();
            withdrawals.withdraw(player, item);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof VaultDepositMenuHolder holder) {
            event.setCancelled(true);
            if (!event.getWhoClicked().getUniqueId().equals(holder.playerUuid())) event.getWhoClicked().closeInventory();
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof VaultMenuHolder holder)) return;
        if (!event.getWhoClicked().getUniqueId().equals(holder.playerUuid())) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    private void openPage(Player player, int page) {
        player.closeInventory();
        Bukkit.dispatchCommand(player, "value " + (page + 1));
    }
}
