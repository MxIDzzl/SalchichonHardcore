package com.escuadronsalchichon.hardcore.trade;

import java.util.Objects;
import java.util.UUID;

public record TradeEscrowRecord(
        UUID operationId, UUID tradeId, UUID ownerUuid, UUID recipientUuid,
        byte[] payload, String fingerprint, String status
) {
    public TradeEscrowRecord {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(tradeId);
        Objects.requireNonNull(ownerUuid);
        Objects.requireNonNull(recipientUuid);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(fingerprint);
        Objects.requireNonNull(status);
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
