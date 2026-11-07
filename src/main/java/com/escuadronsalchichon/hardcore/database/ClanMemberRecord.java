package com.escuadronsalchichon.hardcore.database;

import java.time.Instant;
import java.util.UUID;

public record ClanMemberRecord(UUID playerUuid, UUID clanUuid, String rankId,
                               Instant joinedAt, long kills, long deaths, double kdr) {
    public ClanMemberRecord {
        if (playerUuid == null || clanUuid == null || joinedAt == null
                || rankId == null || rankId.isBlank() || kills < 0 || deaths < 0
                || !Double.isFinite(kdr) || kdr < 0) {
            throw new IllegalArgumentException("Datos de miembro de clan inválidos.");
        }
    }
}
