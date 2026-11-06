package com.escuadronsalchichon.hardcore.database;

import com.escuadronsalchichon.hardcore.player.PlayerData;
import com.escuadronsalchichon.hardcore.trade.TradeEscrowItem;
import com.escuadronsalchichon.hardcore.trade.TradeSessionRecord;
import com.escuadronsalchichon.hardcore.trade.TradeEscrowRecord;

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

    /** Returns deposits quarantined after an interrupted inventory transaction. */
    java.util.List<VaultDepositRecoveryRecord> getUncertainVaultDeposits(UUID uuid);

    /** Records that the marked stack was removed and player data saved durably. */
    boolean markVaultDepositInventoryRemoved(UUID uuid, UUID operationId);

    /** Aborts a quarantined deposit only after inventory recovery proves its marker remains. */
    boolean abortVaultDeposit(UUID uuid, UUID operationId);


    java.util.List<VaultItemRecord> getVaultItems(UUID uuid, int limit, int offset);

    long getPlayerValue(UUID uuid);

    boolean setVaultCooldown(UUID uuid, java.time.Instant nextAllowedAt);

    java.time.Instant getVaultCooldown(UUID uuid);

    VaultClaimRecord createVaultWithdrawal(UUID uuid, UUID itemId, java.time.Instant now, java.time.Instant nextAllowedAt);

    boolean markVaultClaimDelivering(UUID claimId, UUID playerUuid);

    boolean markVaultClaimDelivered(UUID claimId, UUID playerUuid);

    java.util.List<VaultClaimRecord> getPendingVaultClaims(UUID playerUuid);

    /** Returns every nonterminal withdrawal claim for evidence-based recovery. */
    java.util.List<VaultClaimRecord> getUncertainVaultClaims(UUID playerUuid);

    // Item-only trade escrow. All methods are durable and idempotent where noted.
    boolean createTradeSession(UUID tradeId, UUID playerA, UUID playerB);

    TradeSessionRecord getActiveTrade(UUID playerUuid);

    java.util.List<TradeEscrowRecord> getTradeItems(UUID tradeId);

    boolean prepareTradeEscrowItem(TradeEscrowItem item);

    boolean markTradeEscrowed(UUID operationId);

    boolean requestTradeItemReturn(UUID operationId, UUID ownerUuid, long expectedRevision);

    boolean markTradeItemReturned(UUID operationId, UUID ownerUuid);

    boolean advanceTradeRevision(UUID tradeId, UUID actor, long expectedRevision);

    boolean confirmTradeRevision(UUID tradeId, UUID actor, long expectedRevision);

    boolean lockTradeForSettlement(UUID tradeId, long expectedRevision);

    boolean markTradeItemDelivering(UUID operationId, UUID recipientUuid);

    boolean markTradeItemDelivered(UUID operationId, UUID recipientUuid);

    boolean markTradeRecoveryRequired(UUID tradeId);

    boolean completeTrade(UUID tradeId);

    boolean cancelEmptyTrade(UUID tradeId, UUID actor);

    java.util.List<UUID> expireStaleTrades();

    boolean completeExpiredTrade(UUID tradeId);

    boolean updatePlayerSkin(UUID uuid, String textureHash);

    /** Atomically replaces the public clan snapshot imported from zMClans. */
    boolean replaceClanDirectory(java.util.List<ClanRecord> clans, java.util.List<ClanMemberRecord> members);

    VaultRecoverySummary getVaultRecoverySummary();

    int flagUncertainVaultOperations();
}
