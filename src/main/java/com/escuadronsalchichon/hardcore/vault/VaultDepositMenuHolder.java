package com.escuadronsalchichon.hardcore.vault;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.UUID;

public final class VaultDepositMenuHolder implements InventoryHolder {
    private final UUID playerUuid;
    private final List<VaultDepositCandidate> candidates;
    private Inventory inventory;

    public VaultDepositMenuHolder(UUID playerUuid, List<VaultDepositCandidate> candidates) {
        this.playerUuid = playerUuid;
        this.candidates = List.copyOf(candidates);
    }

    public UUID playerUuid() { return playerUuid; }
    public List<VaultDepositCandidate> candidates() { return candidates; }
    public void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
