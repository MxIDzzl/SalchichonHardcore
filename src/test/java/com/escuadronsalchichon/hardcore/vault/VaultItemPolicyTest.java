package com.escuadronsalchichon.hardcore.vault;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VaultItemPolicyTest {
    private final VaultItemPolicy policy = new VaultItemPolicy(
            Map.of(Material.DIAMOND, 10L, Material.EMERALD, 6L), 64);

    @Test
    void creditsOnlyConfiguredPlainStacks() {
        assertEquals(50L, policy.creditFor(new ItemStack(Material.DIAMOND, 5)).orElseThrow());
        assertTrue(policy.creditFor(new ItemStack(Material.GOLD_INGOT, 1)).isEmpty());
    }

    @Test
    void rejectsInvalidAmountsAndOversizedStacks() {
        assertTrue(policy.creditFor(new ItemStack(Material.DIAMOND, 65)).isEmpty());
        assertTrue(policy.creditFor(new ItemStack(Material.AIR)).isEmpty());
    }

    @Test
    void rejectsMetadataBearingItems() {
        ItemStack named = new ItemStack(Material.DIAMOND);
        var meta = named.getItemMeta();
        meta.setDisplayName("Not a plain diamond");
        named.setItemMeta(meta);
        assertTrue(policy.creditFor(named).isEmpty());
    }

    @Test
    void rejectsInvalidPolicyConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new VaultItemPolicy(Map.of(Material.DIAMOND, 0L), 64));
        assertThrows(IllegalArgumentException.class,
                () -> new VaultItemPolicy(Map.of(Material.DIAMOND, 1L), 0));
    }
}
