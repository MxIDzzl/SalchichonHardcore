package com.escuadronsalchichon.hardcore.database;

import java.util.Objects;
import java.util.Set;
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
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final Set<String> SUPPORTED_STATUSES =
            Set.of("PENDING", "DELIVERING", "DELIVERED", "RECOVERY_REQUIRED");

    public VaultClaimRecord {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        if (material.isBlank()) throw new IllegalArgumentException("material must not be blank");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("claim payload size is outside the supported range");
        }
        if (!SUPPORTED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported vault claim status: " + status);
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public RecoveryAction actionFor(int markedAmount) {
        if (markedAmount < 0) return RecoveryAction.MANUAL_REVIEW;
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
