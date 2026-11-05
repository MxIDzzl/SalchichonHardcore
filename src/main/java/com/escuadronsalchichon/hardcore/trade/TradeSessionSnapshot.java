package com.escuadronsalchichon.hardcore.trade;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable trade state. Confirmations are bound to a revision, so any offer
 * change invalidates both participants' previous confirmations.
 */
public record TradeSessionSnapshot(
        UUID tradeId,
        UUID playerA,
        UUID playerB,
        TradeStatus status,
        long revision,
        Long confirmedRevisionA,
        Long confirmedRevisionB
) {
    public TradeSessionSnapshot {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerA, "playerA");
        Objects.requireNonNull(playerB, "playerB");
        Objects.requireNonNull(status, "status");
        if (playerA.equals(playerB)) throw new IllegalArgumentException("A trade needs two distinct players.");
        if (revision < 0) throw new IllegalArgumentException("revision cannot be negative.");
        if (confirmedRevisionA != null && confirmedRevisionA < 0) throw new IllegalArgumentException("invalid confirmation");
        if (confirmedRevisionB != null && confirmedRevisionB < 0) throw new IllegalArgumentException("invalid confirmation");
    }

    public boolean contains(UUID player) {
        return playerA.equals(player) || playerB.equals(player);
    }

    public boolean isConfirmed(UUID player) {
        if (playerA.equals(player)) return Long.valueOf(revision).equals(confirmedRevisionA);
        if (playerB.equals(player)) return Long.valueOf(revision).equals(confirmedRevisionB);
        return false;
    }

    public boolean bothConfirmed() {
        return Long.valueOf(revision).equals(confirmedRevisionA)
                && Long.valueOf(revision).equals(confirmedRevisionB);
    }

    public TradeSessionSnapshot offerChanged() {
        requireOpen();
        return new TradeSessionSnapshot(tradeId, playerA, playerB, TradeStatus.OPEN,
                Math.addExact(revision, 1), null, null);
    }

    public TradeSessionSnapshot confirm(UUID player) {
        requireOpen();
        if (playerA.equals(player)) {
            return new TradeSessionSnapshot(tradeId, playerA, playerB, status,
                    revision, revision, confirmedRevisionB);
        }
        if (playerB.equals(player)) {
            return new TradeSessionSnapshot(tradeId, playerA, playerB, status,
                    revision, confirmedRevisionA, revision);
        }
        throw new IllegalArgumentException("Player is not part of this trade.");
    }

    public TradeSessionSnapshot lockForCommit() {
        requireOpen();
        if (!bothConfirmed()) throw new IllegalStateException("Both players must confirm the current offer revision.");
        return new TradeSessionSnapshot(tradeId, playerA, playerB, TradeStatus.LOCKED,
                revision, confirmedRevisionA, confirmedRevisionB);
    }

    public TradeSessionSnapshot committed() {
        if (status != TradeStatus.LOCKED) throw new IllegalStateException("Only a locked trade can commit.");
        return new TradeSessionSnapshot(tradeId, playerA, playerB, TradeStatus.COMMITTED,
                revision, confirmedRevisionA, confirmedRevisionB);
    }

    public TradeSessionSnapshot cancel(UUID actor) {
        if (status != TradeStatus.OPEN && status != TradeStatus.LOCKED) {
            throw new IllegalStateException("Trade is no longer cancellable.");
        }
        if (!contains(actor)) throw new IllegalArgumentException("Player is not part of this trade.");
        return new TradeSessionSnapshot(tradeId, playerA, playerB, TradeStatus.CANCELLED,
                revision, confirmedRevisionA, confirmedRevisionB);
    }

    public TradeSessionSnapshot requireRecovery() {
        if (status != TradeStatus.LOCKED) throw new IllegalStateException("Only a locked trade can require recovery.");
        return new TradeSessionSnapshot(tradeId, playerA, playerB, TradeStatus.RECOVERY_REQUIRED,
                revision, confirmedRevisionA, confirmedRevisionB);
    }

    private void requireOpen() {
        if (status != TradeStatus.OPEN) throw new IllegalStateException("Trade is not open.");
    }
}
