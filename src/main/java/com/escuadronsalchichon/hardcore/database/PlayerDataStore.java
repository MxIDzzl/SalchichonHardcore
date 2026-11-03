package com.escuadronsalchichon.hardcore.database;

import com.escuadronsalchichon.hardcore.player.PlayerData;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence contract shared by the legacy SQLite store and PostgreSQL.
 *
 * Implementations own their connections and must keep multi-step administrative
 * operations (state change + audit record) atomic.
 */
public interface PlayerDataStore {

    boolean connect();

    boolean createTables();

    void disconnect();

    boolean resetPlayerCounter(UUID targetUuid, String targetName, String adminName, String reason);

    void resetDeathsBefore(LocalDate today);

    boolean incrementTotems(UUID uuid);

    long getTotems(UUID uuid);

    boolean savePlayer(PlayerData playerData);

    PlayerData getPlayer(UUID uuid);

    PlayerData getPlayerByName(String username);

    boolean updateDeathDate(UUID uuid, LocalDate deathDate);

    boolean restorePlayerLife(UUID targetUuid, String targetName, String adminName, String reason);

    boolean recordOfficialDeath(UUID uuid, String username, LocalDate deathDate);

    boolean addPlaytimeTicks(UUID uuid, String username, long ticks);

    long getOfficialDeaths(UUID uuid);

    long getPlaytimeTicks(UUID uuid);

    boolean setOfficialStat(UUID uuid, String statKey, long value, String actor, String reason);
}
