package com.escuadronsalchichon.hardcore.database;

import java.time.Instant;
import java.util.UUID;

/** Immutable item stored in a player's non-transferable Player Value vault. */
public record VaultItemRecord(
        UUID itemId,
        UUID playerUuid,
        String material,
        int amount,
        byte[] payload,
        long creditedValue,
        Instant createdAt
) {
    public VaultItemRecord {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
