package com.escuadronsalchichon.hardcore.database;

import java.util.UUID;

/** Durable withdrawal claim. It must not be replayed automatically after an uncertain delivery. */
public record VaultClaimRecord(
        UUID claimId,
        UUID operationId,
        UUID playerUuid,
        String material,
        int amount,
        byte[] payload,
        String status
) {
    public VaultClaimRecord {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
