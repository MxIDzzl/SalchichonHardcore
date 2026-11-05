package com.escuadronsalchichon.hardcore.trade;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeEscrowItemTest {
    @Test
    void payloadIsDefensivelyCopiedAndFingerprintVerified() {
        byte[] source = {1, 2, 3};
        String fingerprint = TradeEscrowItem.fingerprint(source);
        TradeEscrowItem item = new TradeEscrowItem(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), source, fingerprint);

        source[0] = 99;
        byte[] returned = item.payload();
        returned[1] = 88;

        assertArrayEquals(new byte[]{1, 2, 3}, item.payload());
        assertTrue(item.verifyPayload());
        assertEquals(64, item.fingerprint().length());
    }

    @Test
    void rejectsSelfTransferEmptyPayloadAndFingerprintMismatch() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new TradeEscrowItem(
                UUID.randomUUID(), UUID.randomUUID(), player, player, new byte[]{1},
                TradeEscrowItem.fingerprint(new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> new TradeEscrowItem(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new byte[0], TradeEscrowItem.fingerprint(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> new TradeEscrowItem(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new byte[]{1}, "0".repeat(64)));
    }
}
