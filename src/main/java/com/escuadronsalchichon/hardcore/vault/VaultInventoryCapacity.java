package com.escuadronsalchichon.hardcore.vault;

import org.bukkit.inventory.ItemStack;

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

        int remaining = requested.getAmount();
        for (ItemStack existing : contents) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= requested.getMaxStackSize();
            } else if (existing.isSimilar(requested)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            }

            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }
}
