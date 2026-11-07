package com.escuadronsalchichon.hardcore.vault;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VaultInventoryCapacityTest {

    @Test
    void fitsIntoCompatiblePartialStackWithoutAnEmptySlot() {
        assertTrue(VaultInventoryCapacity.canFit(16, 64,
                List.of(VaultInventoryCapacity.Slot.stack(true, 48, 64))));
    }

    @Test
    void combinesCompatibleStackSpaceAndAnEmptySlot() {
        assertTrue(VaultInventoryCapacity.canFit(68, 64, List.of(
                VaultInventoryCapacity.Slot.stack(true, 60, 64),
                VaultInventoryCapacity.Slot.emptySlot())));
    }

    @Test
    void rejectsWhenCompatibleAndEmptyCapacityAreInsufficient() {
        assertFalse(VaultInventoryCapacity.canFit(69, 64, List.of(
                VaultInventoryCapacity.Slot.stack(true, 60, 64),
                VaultInventoryCapacity.Slot.emptySlot())));
    }

    @Test
    void doesNotCountSpaceInAnIncompatibleStack() {
        assertFalse(VaultInventoryCapacity.canFit(1, 64,
                List.of(VaultInventoryCapacity.Slot.stack(false, 1, 64))));
    }

    @Test
    void rejectsInvalidRequestsAndNullSlotCollections() {
        assertFalse(VaultInventoryCapacity.canFit(0, 64, List.of(VaultInventoryCapacity.Slot.emptySlot())));
        assertFalse(VaultInventoryCapacity.canFit(1, 0, List.of(VaultInventoryCapacity.Slot.emptySlot())));
        assertFalse(VaultInventoryCapacity.canFit(1, 64, null));
    }
}
