package com.escuadronsalchichon.hardcore.trade;

import java.util.UUID;

public record TradeSessionRecord(
        UUID tradeId, UUID playerA, UUID playerB, TradeStatus status,
        long revision, Long confirmedRevisionA, Long confirmedRevisionB
) {
    public TradeSessionRecord {
        if (tradeId == null || playerA == null || playerB == null || status == null) {
            throw new IllegalArgumentException("Trade session fields cannot be null.");
        }
        if (playerA.equals(playerB) || revision < 0) throw new IllegalArgumentException("Invalid trade session.");
    }

    public boolean contains(UUID player) {
        return playerA.equals(player) || playerB.equals(player);
    }

    public UUID other(UUID player) {
        if (playerA.equals(player)) return playerB;
        if (playerB.equals(player)) return playerA;
        throw new IllegalArgumentException("Player is not part of this trade.");
    }

    public boolean bothConfirmed() {
        return Long.valueOf(revision).equals(confirmedRevisionA)
                && Long.valueOf(revision).equals(confirmedRevisionB);
    }
}
