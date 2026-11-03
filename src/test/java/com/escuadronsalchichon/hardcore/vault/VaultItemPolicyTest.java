package com.escuadronsalchichon.hardcore.vault;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VaultItemPolicyTest {
    private final VaultItemPolicy policy = new VaultItemPolicy(
            Map.of("DIAMOND", 10L, "EMERALD", 6L), 64);

    @Test
    void creditsOnlyConfiguredPlainStacks() {
        assertEquals(50L, policy.creditFor("diamond", 5, 64, false).orElseThrow());
        assertTrue(policy.creditFor("GOLD_INGOT", 1, 64, false).isEmpty());
    }

    @Test
    void rejectsInvalidAmountsAndAir() {
        assertTrue(policy.creditFor("DIAMOND", 65, 64, false).isEmpty());
        assertTrue(policy.creditFor("AIR", 1, 64, false).isEmpty());
        assertTrue(policy.creditFor("DIAMOND", 0, 64, false).isEmpty());
        assertTrue(policy.creditFor("DIAMOND", 2, 1, false).isEmpty());
    }

    @Test
    void rejectsMetadataBearingItems() {
        assertTrue(policy.creditFor("DIAMOND", 1, 64, true).isEmpty());
    }

    @Test
    void rejectsInvalidPolicyConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new VaultItemPolicy(Map.of("DIAMOND", 0L), 64));
        assertThrows(IllegalArgumentException.class,
                () -> new VaultItemPolicy(Map.of("AIR", 1L), 64));
        assertThrows(IllegalArgumentException.class,
                () -> new VaultItemPolicy(Map.of("DIAMOND", 1L), 0));
    }
}
