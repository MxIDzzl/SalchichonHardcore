package com.escuadronsalchichon.hardcore.database;

import java.time.Instant;
import java.util.Objects;
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
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        if (material.isBlank()) {
            throw new IllegalArgumentException("material must not be blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (creditedValue < 0) {
            throw new IllegalArgumentException("creditedValue must not be negative");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
