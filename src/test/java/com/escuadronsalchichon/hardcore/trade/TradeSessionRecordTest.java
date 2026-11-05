package com.escuadronsalchichon.hardcore.trade;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeSessionRecordTest {
    @Test
    void exposesOtherParticipantAndRevisionBoundConfirmations() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        TradeSessionRecord record = new TradeSessionRecord(UUID.randomUUID(), a, b,
                TradeStatus.OPEN, 7, 7L, null);

        assertEquals(b, record.other(a));
        assertEquals(a, record.other(b));
        assertTrue(record.isConfirmed(a));
        assertFalse(record.isConfirmed(b));
        assertFalse(record.bothConfirmed());
        assertThrows(IllegalArgumentException.class, () -> record.other(UUID.randomUUID()));
    }

    @Test
    void requiresBothConfirmationsToMatchCurrentRevision() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID id = UUID.randomUUID();

        TradeSessionRecord confirmed = new TradeSessionRecord(id, a, b,
                TradeStatus.OPEN, 3, 3L, 3L);
        TradeSessionRecord stale = new TradeSessionRecord(id, a, b,
                TradeStatus.OPEN, 4, 3L, 4L);

        assertTrue(confirmed.bothConfirmed());
        assertFalse(stale.bothConfirmed());
    }

    @Test
    void rejectsInvalidSessionIdentitiesAndRevision() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new TradeSessionRecord(
                UUID.randomUUID(), player, player, TradeStatus.OPEN, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new TradeSessionRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TradeStatus.OPEN, -1, null, null));
    }
}
