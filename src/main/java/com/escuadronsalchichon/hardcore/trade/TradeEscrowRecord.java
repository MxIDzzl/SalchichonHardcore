package com.escuadronsalchichon.hardcore.trade;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TradeEscrowRecord(
        UUID operationId, UUID tradeId, UUID ownerUuid, UUID recipientUuid,
        byte[] payload, String fingerprint, String status
) {
    private static final Set<String> SUPPORTED_STATUSES = Set.of(
            "PREPARED", "ESCROWED", "DELIVERY_PENDING", "DELIVERING",
            "DELIVERED", "RETURN_PENDING", "RETURNED", "RECOVERY_REQUIRED");

    public TradeEscrowRecord {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(recipientUuid, "recipientUuid");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(status, "status");
        if (ownerUuid.equals(recipientUuid)) throw new IllegalArgumentException("Escrow owner and recipient must differ.");
        if (payload.length == 0 || payload.length > TradeEscrowItem.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Escrow payload size is outside the supported range.");
        }
        if (!SUPPORTED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported trade item operation status: " + status);
        }
        payload = payload.clone();
        if (!TradeEscrowItem.fingerprint(payload).equalsIgnoreCase(fingerprint)) {
            throw new IllegalArgumentException("Trade escrow fingerprint mismatch.");
        }
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
