package com.escuadronsalchichon.hardcore.database;

import com.escuadronsalchichon.hardcore.player.PlayerData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MySqlDatabaseManagerIntegrationTest {
    private MySqlDatabaseManager manager;

    @BeforeEach
    void connectToMySql() throws Exception {
        String url = System.getenv("SALCHICHON_MYSQL_TEST_URL");
        Assumptions.assumeTrue(url != null, "MySQL integration service is not configured");

        JavaPlugin plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MySqlDatabaseManagerIntegrationTest"));
        when(plugin.getConfig()).thenReturn(config);
        when(config.getString("settings.timezone", "America/Mexico_City")).thenReturn("UTC");

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SalchichonHardcore-MySQL-Integration");
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.setJdbcUrl(url);
        hikari.setUsername(System.getenv("SALCHICHON_MYSQL_TEST_USER"));
        hikari.setPassword(System.getenv("SALCHICHON_MYSQL_TEST_PASSWORD"));
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(0);
        hikari.setConnectionInitSql("SET time_zone = '+00:00'");

        MySqlConnectionPool pool = new MySqlConnectionPool(plugin, new HikariDataSource(hikari));
        manager = new MySqlDatabaseManager(plugin, pool);
        assertTrue(manager.connect());
        assertTrue(manager.createTables());
    }

    @AfterEach
    void disconnectFromMySql() {
        if (manager != null) manager.disconnect();
    }

    @Test
    void persistsOfficialStatsAndTotemsIdempotently() {
        UUID playerId = UUID.randomUUID();
        assertTrue(manager.savePlayer(new PlayerData(playerId, "PersistenceTest", null)));

        LocalDate today = LocalDate.of(2026, 11, 6);
        assertTrue(manager.recordOfficialDeath(playerId, "PersistenceTest", today));
        assertTrue(manager.recordOfficialDeath(playerId, "PersistenceTest", today));
        assertEquals(1L, manager.getOfficialDeaths(playerId));

        assertTrue(manager.incrementTotems(playerId));
        assertEquals(1L, manager.getTotems(playerId));
    }

    @Test
    void commitsRepeatedVaultDepositsWithoutLosingValueOnMysqlUpsertCounts() {
        VaultRecoverySummary before = manager.getVaultRecoverySummary();
        UUID playerId = UUID.randomUUID();
        assertTrue(manager.savePlayer(new PlayerData(playerId, "VaultTester", null)));

        VaultItemRecord first = item(playerId, 20);
        assertTrue(manager.prepareVaultDeposit(playerId, "VaultTester", first));
        assertTrue(manager.markVaultDepositInventoryRemoved(playerId, first.itemId()));
        assertTrue(manager.commitVaultDeposit(playerId, first.itemId()));
        assertEquals(20L, manager.getPlayerValue(playerId));

        // A retry after commit is idempotent and must not credit the same item twice.
        assertTrue(manager.commitVaultDeposit(playerId, first.itemId()));
        assertEquals(20L, manager.getPlayerValue(playerId));

        // The second deposit exercises MySQL's duplicate-key affected-row count (2).
        VaultItemRecord second = item(playerId, 15);
        assertTrue(manager.prepareVaultDeposit(playerId, "VaultTester", second));
        assertTrue(manager.markVaultDepositInventoryRemoved(playerId, second.itemId()));
        assertTrue(manager.commitVaultDeposit(playerId, second.itemId()));
        assertEquals(35L, manager.getPlayerValue(playerId));
        assertEquals(2, manager.getVaultItems(playerId, 20, 0).size());

        VaultRecoverySummary recovery = manager.getVaultRecoverySummary();
        assertNotNull(recovery);
        assertEquals(before.preparedDeposits(), recovery.preparedDeposits());
        assertEquals(before.pendingWithdrawals(), recovery.pendingWithdrawals());
        assertEquals(before.recoveryRequiredDeposits(), recovery.recoveryRequiredDeposits());
        assertEquals(before.recoveryRequiredWithdrawals(), recovery.recoveryRequiredWithdrawals());
    }

    @Test
    void withdrawsAtomicallyAndTracksDeliveryUntilAcknowledged() {
        UUID playerId = UUID.randomUUID();
        assertTrue(manager.savePlayer(new PlayerData(playerId, "WithdrawalTester", null)));
        VaultItemRecord deposited = item(playerId, 25);
        assertTrue(manager.prepareVaultDeposit(playerId, "WithdrawalTester", deposited));
        assertTrue(manager.markVaultDepositInventoryRemoved(playerId, deposited.itemId()));
        assertTrue(manager.commitVaultDeposit(playerId, deposited.itemId()));
        assertEquals(25L, manager.getPlayerValue(playerId));

        Instant now = Instant.now();
        VaultClaimRecord claim = manager.createVaultWithdrawal(
                playerId, deposited.itemId(), now, now.plusSeconds(30));

        assertNotNull(claim);
        assertEquals("PENDING", claim.status());
        assertEquals(0L, manager.getPlayerValue(playerId));
        assertTrue(manager.getVaultItems(playerId, 20, 0).isEmpty());
        assertEquals(1, manager.getUncertainVaultClaims(playerId).size());

        assertTrue(manager.markVaultClaimDelivering(claim.claimId(), playerId));
        assertEquals("DELIVERING", manager.getUncertainVaultClaims(playerId).get(0).status());
        assertTrue(manager.markVaultClaimDelivered(claim.claimId(), playerId));
        assertTrue(manager.getUncertainVaultClaims(playerId).isEmpty());
        assertNull(manager.createVaultWithdrawal(
                playerId, deposited.itemId(), now.plusSeconds(31), now.plusSeconds(61)));
    }

    @Test
    void serializesFirstWithdrawalsForOnePlayerAndHonorsCooldown() throws Exception {
        UUID playerId = UUID.randomUUID();
        assertTrue(manager.savePlayer(new PlayerData(playerId, "ConcurrentVault", null)));
        VaultItemRecord first = item(playerId, 10);
        VaultItemRecord second = item(playerId, 20);
        deposit(playerId, first);
        deposit(playerId, second);

        Instant now = Instant.now();
        Instant nextAllowed = now.plusSeconds(60);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<VaultClaimRecord> firstAttempt = executor.submit(() -> {
                start.await();
                return manager.createVaultWithdrawal(playerId, first.itemId(), now, nextAllowed);
            });
            Future<VaultClaimRecord> secondAttempt = executor.submit(() -> {
                start.await();
                return manager.createVaultWithdrawal(playerId, second.itemId(), now, nextAllowed);
            });
            start.countDown();

            VaultClaimRecord firstResult = firstAttempt.get();
            VaultClaimRecord secondResult = secondAttempt.get();
            assertNotEquals(firstResult == null, secondResult == null,
                    "Exactly one simultaneous first withdrawal should be accepted");
            assertEquals(1, manager.getVaultItems(playerId, 20, 0).size());
            assertEquals(firstResult == null ? 10L : 20L, manager.getPlayerValue(playerId));
        } finally {
            executor.shutdownNow();
        }
    }

    private void deposit(UUID playerId, VaultItemRecord item) {
        assertTrue(manager.prepareVaultDeposit(playerId, "ConcurrentVault", item));
        assertTrue(manager.markVaultDepositInventoryRemoved(playerId, item.itemId()));
        assertTrue(manager.commitVaultDeposit(playerId, item.itemId()));
    }

    private static VaultItemRecord item(UUID playerId, long value) {
        return new VaultItemRecord(UUID.randomUUID(), playerId, "DIAMOND", 1,
                new byte[] {1, 2, 3, 4}, value, Instant.now());
    }
}
