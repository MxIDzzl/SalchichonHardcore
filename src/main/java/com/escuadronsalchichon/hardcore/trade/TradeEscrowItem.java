package com.escuadronsalchichon.hardcore.trade;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-side escrow payload with a SHA-256 integrity fingerprint. */
public record TradeEscrowItem(
        UUID escrowItemId,
        UUID tradeId,
        UUID ownerUuid,
        UUID recipientUuid,
        byte[] payload,
        String fingerprint
) {
    public TradeEscrowItem {
        Objects.requireNonNull(escrowItemId, "escrowItemId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(recipientUuid, "recipientUuid");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (ownerUuid.equals(recipientUuid)) throw new IllegalArgumentException("Escrow owner and recipient must differ.");
        if (payload.length == 0) throw new IllegalArgumentException("Escrow payload cannot be empty.");
        payload = payload.clone();
        String actual = fingerprint(payload);
        if (!actual.equalsIgnoreCase(fingerprint)) throw new IllegalArgumentException("Escrow fingerprint mismatch.");
        fingerprint = actual;
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public static String fingerprint(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    public boolean verifyPayload() {
        return fingerprint(payload).equals(fingerprint);
    }
}
