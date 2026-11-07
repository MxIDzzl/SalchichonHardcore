package com.escuadronsalchichon.hardcore.database;

import java.time.Instant;
import java.util.UUID;

public record ClanRecord(UUID clanId, String name, String tag, String description,
                         UUID leaderUuid, Instant createdAt, int memberCount, int maxMembers,
                         long points, int level, long kills, long deaths, double kdr) {
    public ClanRecord {
        if (clanId == null || leaderUuid == null || createdAt == null
                || name == null || name.isBlank() || tag == null
                || memberCount < 0 || maxMembers < 0 || points < 0
                || level < 0 || kills < 0 || deaths < 0
                || !Double.isFinite(kdr) || kdr < 0) {
            throw new IllegalArgumentException("Datos de clan inválidos.");
        }
        description = description == null ? "" : description;
    }
}
