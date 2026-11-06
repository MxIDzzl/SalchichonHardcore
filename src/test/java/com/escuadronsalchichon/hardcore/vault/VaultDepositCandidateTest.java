package com.escuadronsalchichon.hardcore.vault;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VaultDepositCandidateTest {
    @Test
    void snapshotsTheItemAndFingerprintDefensively() {
        ItemStack source = new ItemStack(Material.DIAMOND, 3);
        byte[] fingerprint = source.serializeAsBytes();
        VaultDepositCandidate candidate = new VaultDepositCandidate(4, source, 30, fingerprint);

        source.setAmount(9);
        fingerprint[0] ^= 1;

        assertEquals(3, candidate.item().getAmount());
        assertArrayEquals(new ItemStack(Material.DIAMOND, 3).serializeAsBytes(), candidate.fingerprint());
        ItemStack returned = candidate.item();
        returned.setAmount(1);
        assertEquals(3, candidate.item().getAmount());
    }

    @Test
    void rejectsSlotsOutsidePlayerStorageAndNonPositiveCredit() {
        ItemStack item = new ItemStack(Material.DIAMOND, 1);
        byte[] fingerprint = item.serializeAsBytes();
        assertThrows(IllegalArgumentException.class,
                () -> new VaultDepositCandidate(36, item, 10, fingerprint));
        assertThrows(IllegalArgumentException.class,
                () -> new VaultDepositCandidate(0, item, 0, fingerprint));
    }
}
