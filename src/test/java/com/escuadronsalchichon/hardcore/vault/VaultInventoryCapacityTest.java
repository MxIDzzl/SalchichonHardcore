package com.escuadronsalchichon.hardcore.vault;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VaultInventoryCapacityTest {

    @Test
    void fitsIntoCompatiblePartialStackWithoutAnEmptySlot() {
        ItemStack existing = new ItemStack(Material.DIAMOND, 48);
        ItemStack requested = new ItemStack(Material.DIAMOND, 16);

        assertTrue(VaultInventoryCapacity.canFit(requested, new ItemStack[] {existing}));
    }

    @Test
    void combinesCompatibleStackSpaceAndAnEmptySlot() {
        ItemStack existing = new ItemStack(Material.DIAMOND, 60);
        ItemStack requested = new ItemStack(Material.DIAMOND, 68);

        assertTrue(VaultInventoryCapacity.canFit(requested, new ItemStack[] {existing, null}));
    }

    @Test
    void rejectsWhenCompatibleAndEmptyCapacityAreInsufficient() {
        ItemStack existing = new ItemStack(Material.DIAMOND, 60);
        ItemStack requested = new ItemStack(Material.DIAMOND, 69);

        assertFalse(VaultInventoryCapacity.canFit(requested, new ItemStack[] {existing, null}));
    }

    @Test
    void doesNotCountSpaceInAnIncompatibleStack() {
        ItemStack existing = new ItemStack(Material.EMERALD, 1);
        ItemStack requested = new ItemStack(Material.DIAMOND, 1);

        assertFalse(VaultInventoryCapacity.canFit(requested, new ItemStack[] {existing}));
    }

    @Test
    void rejectsNullOrAirRequests() {
        assertFalse(VaultInventoryCapacity.canFit(null, new ItemStack[] {null}));
        assertFalse(VaultInventoryCapacity.canFit(new ItemStack(Material.AIR), new ItemStack[] {null}));
    }
}
