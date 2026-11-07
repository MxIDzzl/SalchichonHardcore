package com.escuadronsalchichon.hardcore.vault;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculates whether a requested stack fits in storage slots without mutating the inventory.
 */
final class VaultInventoryCapacity {
    private VaultInventoryCapacity() {
    }

    static boolean canFit(ItemStack requested, ItemStack[] contents) {
        if (requested == null || requested.getType().isAir() || contents == null) {
            return false;
        }

        List<Slot> slots = new ArrayList<>(contents.length);
        for (ItemStack existing : contents) {
            if (existing == null || existing.getType().isAir()) {
                slots.add(Slot.emptySlot());
            } else {
                slots.add(Slot.stack(existing.isSimilar(requested),
                        existing.getAmount(), existing.getMaxStackSize()));
            }
        }
        return canFit(requested.getAmount(), requested.getMaxStackSize(), slots);
    }

    static boolean canFit(int requestedAmount, int requestedMaxStackSize, List<Slot> slots) {
        if (requestedAmount <= 0 || requestedMaxStackSize <= 0 || slots == null) {
            return false;
        }

        int remaining = requestedAmount;
        for (Slot slot : slots) {
            if (slot == null) continue;
            if (slot.empty()) {
                remaining -= requestedMaxStackSize;
            } else if (slot.similar()) {
                remaining -= Math.max(0, slot.maxStackSize() - slot.amount());
            }

            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    record Slot(boolean empty, boolean similar, int amount, int maxStackSize) {
        static Slot emptySlot() {
            return new Slot(true, false, 0, 0);
        }

        static Slot stack(boolean similar, int amount, int maxStackSize) {
            return new Slot(false, similar, amount, maxStackSize);
        }
    }
}
