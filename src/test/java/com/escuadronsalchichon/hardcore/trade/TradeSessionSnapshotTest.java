package com.escuadronsalchichon.hardcore.trade;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeSessionSnapshotTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private TradeSessionSnapshot open() {
        return new TradeSessionSnapshot(UUID.randomUUID(), a, b, TradeStatus.OPEN, 0, null, null);
    }

    @Test
    void changingOfferIncrementsRevisionAndInvalidatesBothConfirmations() {
        TradeSessionSnapshot confirmed = open().confirm(a).confirm(b);
        assertTrue(confirmed.bothConfirmed());

        TradeSessionSnapshot changed = confirmed.offerChanged();

        assertEquals(1, changed.revision());
        assertFalse(changed.isConfirmed(a));
        assertFalse(changed.isConfirmed(b));
        assertFalse(changed.bothConfirmed());
    }

    @Test
    void cannotLockUntilBothPlayersConfirmSameRevision() {
        TradeSessionSnapshot onlyA = open().confirm(a);
        assertThrows(IllegalStateException.class, onlyA::lockForCommit);

        TradeSessionSnapshot both = onlyA.confirm(b);
        assertEquals(TradeStatus.LOCKED, both.lockForCommit().status());
    }

    @Test
    void confirmationIsBoundToRevisionAndOnlyParticipantsCanConfirm() {
        TradeSessionSnapshot first = open().confirm(a);
        TradeSessionSnapshot changed = first.offerChanged();

        assertFalse(changed.isConfirmed(a));
        assertThrows(IllegalArgumentException.class, () -> changed.confirm(UUID.randomUUID()));
    }

    @Test
    void lockedTradeMustCommitOrEnterRecovery() {
        TradeSessionSnapshot locked = open().confirm(a).confirm(b).lockForCommit();
        assertEquals(TradeStatus.COMMITTED, locked.committed().status());
        assertEquals(TradeStatus.RECOVERY_REQUIRED, locked.requireRecovery().status());
        assertThrows(IllegalStateException.class, () -> open().committed());
    }

    @Test
    void participantsMustBeDistinct() {
        assertThrows(IllegalArgumentException.class, () ->
                new TradeSessionSnapshot(UUID.randomUUID(), a, a, TradeStatus.OPEN, 0, null, null));
    }
}
