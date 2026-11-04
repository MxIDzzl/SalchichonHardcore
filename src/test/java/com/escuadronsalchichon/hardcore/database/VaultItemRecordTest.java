package com.escuadronsalchichon.hardcore.database;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultItemRecordTest {
    private static VaultItemRecord validRecord(byte[] payload) {
        return new VaultItemRecord(UUID.randomUUID(), UUID.randomUUID(),
                "DIAMOND", 3, payload, 30L, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void protectsPayloadFromExternalMutation() {
        byte[] original = {1, 2, 3};
        VaultItemRecord item = validRecord(original);
        original[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, item.payload());
        byte[] returned = item.payload();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, item.payload());
    }

    @Test
    void rejectsInvalidAmount() {
        assertThrows(IllegalArgumentException.class, () -> new VaultItemRecord(
                UUID.randomUUID(), UUID.randomUUID(), "DIAMOND", 0, new byte[]{1},
                10L, Instant.now()));
    }

    @Test
    void rejectsNegativeCredit() {
        assertThrows(IllegalArgumentException.class, () -> new VaultItemRecord(
                UUID.randomUUID(), UUID.randomUUID(), "DIAMOND", 1, new byte[]{1},
                -1L, Instant.now()));
    }

    @Test
    void rejectsBlankMaterial() {
        assertThrows(IllegalArgumentException.class, () -> new VaultItemRecord(
                UUID.randomUUID(), UUID.randomUUID(), " ", 1, new byte[]{1},
                0L, Instant.now()));
    }

    @Test
    void rejectsNullPayload() {
        assertThrows(NullPointerException.class, () -> new VaultItemRecord(
                UUID.randomUUID(), UUID.randomUUID(), "DIAMOND", 1, null,
                0L, Instant.now()));
    }
}
