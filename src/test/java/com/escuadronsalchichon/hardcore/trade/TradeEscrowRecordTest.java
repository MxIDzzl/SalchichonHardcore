package com.escuadronsalchichon.hardcore.trade;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeEscrowRecordTest {
    @Test
    void defensivelyCopiesPayloadAndValidatesFingerprint() {
        byte[] payload = {4, 5, 6};
        TradeEscrowRecord record = new TradeEscrowRecord(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), payload,
                TradeEscrowItem.fingerprint(payload), "ESCROWED");

        payload[0] = 0;
        byte[] copy = record.payload();
        copy[1] = 0;

        assertArrayEquals(new byte[]{4, 5, 6}, record.payload());
    }

    @Test
    void rejectsOversizedPayloadFromPersistence() {
        byte[] oversized = new byte[TradeEscrowItem.MAX_PAYLOAD_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> new TradeEscrowRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                oversized, TradeEscrowItem.fingerprint(oversized), "ESCROWED"));
    }

    @Test
    void rejectsSelfTransfer() {
        UUID player = UUID.randomUUID();
        byte[] payload = {1};
        assertThrows(IllegalArgumentException.class, () -> new TradeEscrowRecord(
                UUID.randomUUID(), UUID.randomUUID(), player, player,
                payload, TradeEscrowItem.fingerprint(payload), "ESCROWED"));
    }

    @Test
    void rejectsFingerprintMismatch() {
        byte[] payload = {1};
        assertThrows(IllegalArgumentException.class, () -> new TradeEscrowRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                payload, "0".repeat(64), "ESCROWED"));
    }
}
