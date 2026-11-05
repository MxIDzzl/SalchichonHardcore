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

    boolean depositVaultItem(UUID uuid, String username, VaultItemRecord item);

    /** Persists a PREPARED journal entry before any inventory mutation. */
    boolean prepareVaultDeposit(UUID uuid, String username, VaultItemRecord item);

    /** Commits a previously prepared deposit and its score in one transaction. */
    boolean commitVaultDeposit(UUID uuid, UUID operationId);


    java.util.List<VaultItemRecord> getVaultItems(UUID uuid, int limit, int offset);

    long getPlayerValue(UUID uuid);

    boolean setVaultCooldown(UUID uuid, java.time.Instant nextAllowedAt);

    java.time.Instant getVaultCooldown(UUID uuid);

    VaultClaimRecord createVaultWithdrawal(UUID uuid, UUID itemId, java.time.Instant now, java.time.Instant nextAllowedAt);

    boolean markVaultClaimDelivering(UUID claimId, UUID playerUuid);

    boolean markVaultClaimDelivered(UUID claimId, UUID playerUuid);

    java.util.List<VaultClaimRecord> getPendingVaultClaims(UUID playerUuid);

    boolean updatePlayerSkin(UUID uuid, String textureHash);

    VaultRecoverySummary getVaultRecoverySummary();

    int flagUncertainVaultOperations();
}
