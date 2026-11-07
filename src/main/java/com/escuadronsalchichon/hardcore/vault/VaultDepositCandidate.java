package com.escuadronsalchichon.hardcore.vault;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** Immutable snapshot of an eligible inventory stack shown in the deposit menu. */
public record VaultDepositCandidate(int inventorySlot, ItemStack item, long credit, byte[] fingerprint) {
    public VaultDepositCandidate {
        if (inventorySlot < 0 || inventorySlot >= 36 || credit < 1) {
            throw new IllegalArgumentException("Invalid deposit candidate");
        }
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(fingerprint, "fingerprint");
        item = item.clone();
        fingerprint = fingerprint.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    @Override
    public byte[] fingerprint() {
        return fingerprint.clone();
    }
}
