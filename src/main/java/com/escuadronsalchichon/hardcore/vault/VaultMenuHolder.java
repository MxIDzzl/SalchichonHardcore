package com.escuadronsalchichon.hardcore.vault;

import com.escuadronsalchichon.hardcore.database.VaultItemRecord;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.UUID;

public final class VaultMenuHolder implements InventoryHolder {
    private final UUID playerUuid;
    private final int page;
    private final long playerValue;
    private final List<VaultItemRecord> items;
    private final boolean hasPrevious;
    private final boolean hasNext;
    private Inventory inventory;

    public VaultMenuHolder(UUID playerUuid, int page, long playerValue,
                           List<VaultItemRecord> items, boolean hasPrevious, boolean hasNext) {
        this.playerUuid = playerUuid;
        this.page = page;
        this.playerValue = playerValue;
        this.items = List.copyOf(items);
        this.hasPrevious = hasPrevious;
        this.hasNext = hasNext;
    }

    public UUID playerUuid() { return playerUuid; }
    public int page() { return page; }
    public long playerValue() { return playerValue; }
    public List<VaultItemRecord> items() { return items; }
    public boolean hasPrevious() { return hasPrevious; }
    public boolean hasNext() { return hasNext; }
    public void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
