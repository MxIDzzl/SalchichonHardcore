package com.escuadronsalchichon.hardcore.database;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MySqlIntegrationTest {

    @BeforeAll
    static void initializeSchema() throws SQLException, IOException {
        Assumptions.assumeTrue(System.getenv("SALCHICHON_MYSQL_TEST_URL") != null,
                "Set SALCHICHON_MYSQL_TEST_URL to run MySQL integration tests");
        String schema;
        try (var stream = MySqlIntegrationTest.class.getResourceAsStream("/db/mysql/schema-v1.sql")) {
            assertNotNull(stream, "MySQL schema resource must be packaged");
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String part : schema.split(";")) {
                String sql = part.lines()
                        .filter(line -> !line.stripLeading().startsWith("--"))
                        .reduce("", (left, right) -> left + right + "\n")
                        .trim();
                if (!sql.isEmpty()) statement.execute(sql);
            }
        }
    }

    @Test
    void mysqlSchemaSupportsPlayerValueUpserts() throws SQLException {
        UUID player = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertPlayer(connection, player);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO player_stats(player_uuid, player_value, updated_at)
                    VALUES (?, ?, UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE
                        player_value=player_stats.player_value+VALUES(player_value),
                        updated_at=UTC_TIMESTAMP(6)
                    """)) {
                insert.setString(1, player.toString());
                insert.setLong(2, 10);
                assertEquals(1, insert.executeUpdate());
                insert.setLong(2, 20);
                assertEquals(2, insert.executeUpdate());
            }
            try (var query = connection.prepareStatement(
                    "SELECT player_value FROM player_stats WHERE player_uuid=?")) {
                query.setString(1, player.toString());
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(30L, rows.getLong(1));
                }
            }
        }
    }

    @Test
    void generatedUniqueKeyPreventsConcurrentActiveVaultOperations() throws SQLException {
        UUID player = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertPlayer(connection, player);
            insertVaultOperation(connection, UUID.randomUUID(), player, "PREPARED");
            assertThrows(SQLException.class,
                    () -> insertVaultOperation(connection, UUID.randomUUID(), player, "PREPARED"));
            try (var finish = connection.prepareStatement("""
                    UPDATE vault_operations SET status='COMMITTED'
                    WHERE player_uuid=? AND status='PREPARED'
                    """)) {
                finish.setString(1, player.toString());
                assertEquals(1, finish.executeUpdate());
            }
            assertDoesNotThrow(
                    () -> insertVaultOperation(connection, UUID.randomUUID(), player, "PREPARED"));
        }
    }

    @Test
    void innodbRollsBackPlayerValueJournalAndStatsTogether() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertPlayer(connection, player);
            connection.setAutoCommit(false);
            try {
                insertVaultOperation(connection, operation, player, "PREPARED");
                try (var stats = connection.prepareStatement("""
                        INSERT INTO player_stats(player_uuid, player_value, updated_at)
                        VALUES (?, 50, UTC_TIMESTAMP(6))
                        """)) {
                    stats.setString(1, player.toString());
                    stats.executeUpdate();
                }
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }
            assertEquals(0, count(connection, "vault_operations", operation.toString()));
            assertEquals(0, count(connection, "player_stats", player.toString()));
        }
    }

    @Test
    void mysqlRecoverySummaryUsesPortableConditionalAggregates() throws SQLException {
        UUID player = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertPlayer(connection, player);
            insertVaultOperation(connection, UUID.randomUUID(), player, "DEPOSIT", "PREPARED");
            insertVaultOperation(connection, UUID.randomUUID(), player, "WITHDRAW", "DELIVERY_PENDING");
            insertVaultOperation(connection, UUID.randomUUID(), player, "DEPOSIT", "RECOVERY_REQUIRED");
            insertVaultOperation(connection, UUID.randomUUID(), player, "WITHDRAW", "RECOVERY_REQUIRED");
            try (var query = connection.prepareStatement("""
                    SELECT
                        COALESCE(SUM(CASE WHEN operation_type='DEPOSIT' AND status IN ('PREPARED','DELIVERY_PENDING') THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN operation_type='WITHDRAW' AND status='DELIVERY_PENDING' THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN operation_type='DEPOSIT' AND status='RECOVERY_REQUIRED' THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN operation_type='WITHDRAW' AND status='RECOVERY_REQUIRED' THEN 1 ELSE 0 END), 0)
                    FROM vault_operations
                    """);
                 var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals(1, rows.getInt(2));
                assertEquals(1, rows.getInt(3));
                assertEquals(1, rows.getInt(4));
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                System.getenv("SALCHICHON_MYSQL_TEST_URL"),
                System.getenv("SALCHICHON_MYSQL_TEST_USER"),
                System.getenv("SALCHICHON_MYSQL_TEST_PASSWORD"));
    }

    private static void insertPlayer(Connection connection, UUID player) throws SQLException {
        try (var insert = connection.prepareStatement(
                "INSERT INTO player_data(uuid, username) VALUES (?, 'TestPlayer')")) {
            insert.setString(1, player.toString());
            insert.executeUpdate();
        }
    }

    private static void insertVaultOperation(Connection connection, UUID operation, UUID player, String status)
            throws SQLException {
        insertVaultOperation(connection, operation, player, "DEPOSIT", status);
    }

    private static void insertVaultOperation(Connection connection, UUID operation, UUID player,
                                             String operationType, String status) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO vault_operations(
                    operation_id, player_uuid, operation_type, status,
                    material, amount, item_payload, credited_value
                ) VALUES (?, ?, ?, ?, 'DIAMOND', 1, ?, 10)
                """)) {
            insert.setString(1, operation.toString());
            insert.setString(2, player.toString());
            insert.setString(3, operationType);
            insert.setString(4, status);
            insert.setBytes(5, new byte[] {1, 2, 3});
            insert.executeUpdate();
        }
    }

    private static long count(Connection connection, String table, String id) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE " + (table.equals("player_stats")
                        ? "player_uuid" : "operation_id") + "=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
