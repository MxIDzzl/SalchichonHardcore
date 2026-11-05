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

    public RecoveryAction actionFor(int markedAmount) {
        if (status.equals("PENDING") && markedAmount == 0) return RecoveryAction.DELIVER_PENDING;
        if ((status.equals("DELIVERING") || status.equals("RECOVERY_REQUIRED"))
                && markedAmount == amount) return RecoveryAction.ACKNOWLEDGE_DELIVERY;
        return RecoveryAction.MANUAL_REVIEW;
    }

    public enum RecoveryAction {
        DELIVER_PENDING,
        ACKNOWLEDGE_DELIVERY,
        MANUAL_REVIEW
    }
}
