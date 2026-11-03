package com.escuadronsalchichon.hardcore.database;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VaultItemRecordTest {
    @Test void protectsPayloadFromExternalMutation() {
        byte[] original = {1, 2, 3};
        VaultItemRecord item = new VaultItemRecord(UUID.randomUUID(), UUID.randomUUID(),
                "DIAMOND", 3, original, 30L, Instant.parse("2026-01-01T00:00:00Z"));
        original[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, item.payload());
        byte[] returned = item.payload();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, item.payload());
    }
}
