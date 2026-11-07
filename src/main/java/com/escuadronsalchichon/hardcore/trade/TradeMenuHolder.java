package com.escuadronsalchichon.hardcore.trade;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.UUID;

public final class TradeMenuHolder implements InventoryHolder {
    private final UUID viewer;
    private final TradeSessionRecord session;
    private final List<TradeEscrowRecord> items;
    private final int page;
    private final boolean hasPrevious;
    private final boolean hasNext;
    private Inventory inventory;

    public TradeMenuHolder(UUID viewer, TradeSessionRecord session, List<TradeEscrowRecord> items,
                           int page, boolean hasPrevious, boolean hasNext) {
        this.viewer = viewer;
        this.session = session;
        this.items = List.copyOf(items);
        this.page = page;
        this.hasPrevious = hasPrevious;
        this.hasNext = hasNext;
    }

    public UUID viewer() { return viewer; }
    public TradeSessionRecord session() { return session; }
    public List<TradeEscrowRecord> items() { return items; }
    public int page() { return page; }
    public boolean hasPrevious() { return hasPrevious; }
    public boolean hasNext() { return hasNext; }
    public void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
