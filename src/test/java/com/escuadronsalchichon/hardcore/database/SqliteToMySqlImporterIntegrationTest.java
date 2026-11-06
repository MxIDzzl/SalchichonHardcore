package com.escuadronsalchichon.hardcore.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqliteToMySqlImporterIntegrationTest {

    @Test
    void importsValidatedSqliteSnapshotIntoAnEmptyMysqlDatabase() throws Exception {
        String baseUrl = System.getenv("SALCHICHON_MYSQL_TEST_URL");
        Assumptions.assumeTrue(baseUrl != null, "MySQL integration service is not configured");

        String rootUser = System.getenv("SALCHICHON_MYSQL_TEST_USER");
        String rootPassword = System.getenv("SALCHICHON_MYSQL_TEST_PASSWORD");
        String serverUrl = baseUrl.replace("/salchichon?", "/?");
        String destinationUrl = baseUrl.replace("/salchichon?", "/salchichon_import?");

        try (Connection root = DriverManager.getConnection(serverUrl, rootUser, rootPassword);
             Statement statement = root.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS salchichon_import CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        Path sqliteFile = Files.createTempFile("salchichon-import-", ".db");
        try {
            createSqliteSource(sqliteFile);

            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("SqliteToMySqlImporterIntegrationTest"));

            HikariConfig config = new HikariConfig();
            config.setPoolName("Salchichon-Importer-Integration");
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl(destinationUrl);
            config.setUsername(rootUser);
            config.setPassword(rootPassword);
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setConnectionInitSql("SET time_zone = '+00:00'");

            MySqlConnectionPool pool = new MySqlConnectionPool(plugin, new HikariDataSource(config));
            try {
                assertTrue(pool.isRunning());
                new MySqlSchemaManager(plugin, pool).initialize();

                SqliteToMySqlImporter importer = new SqliteToMySqlImporter(plugin, pool);
                SqliteToMySqlImporter.ImportReport report = importer.importDatabase(
                        sqliteFile.toFile(), ZoneId.of("UTC"));

                assertEquals(1, report.playersImported());
                assertEquals(1, report.statsImported());
                assertEquals(1, report.skinsImported());
                assertEquals(1, report.statEventsImported());
                assertEquals(1, report.auditImported());

                try (Connection destination = pool.getConnection()) {
                    assertEquals(1, count(destination, "player_data"));
                    assertEquals(1, count(destination, "player_stats"));
                    assertEquals(1, count(destination, "player_skins"));
                    assertEquals(1, count(destination, "stat_events"));
                    assertEquals(1, count(destination, "admin_audit"));
                    try (Statement query = destination.createStatement();
                         ResultSet row = query.executeQuery("SELECT player_value FROM player_stats LIMIT 1")) {
                        assertTrue(row.next());
                        assertEquals(99L, row.getLong(1));
                    }
                    try (Statement query = destination.createStatement();
                         ResultSet row = query.executeQuery("SELECT texture_hash FROM player_skins LIMIT 1")) {
                        assertTrue(row.next());
                        assertEquals("abcdef0123456789abcdef0123456789", row.getString(1));
                    }
                }
                assertTrue(Files.size(sqliteFile) > 0, "SQLite source must remain intact");
            } finally {
                pool.close();
            }
        } finally {
            Files.deleteIfExists(sqliteFile);
        }
    }

    private static void createSqliteSource(Path file) throws SQLException {
        String uuid = "9b9aaf67-3c58-42a9-9b3b-fd7f6b1b4c1d";
        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = source.createStatement()) {
            statement.execute("CREATE TABLE player_data(uuid TEXT PRIMARY KEY, username TEXT NOT NULL, last_death_date TEXT, totem_uses INTEGER NOT NULL)");
            statement.execute("CREATE TABLE player_stats(player_uuid TEXT PRIMARY KEY, official_deaths INTEGER NOT NULL, playtime_ticks INTEGER NOT NULL, player_value INTEGER NOT NULL)");
            statement.execute("CREATE TABLE player_skins(player_uuid TEXT PRIMARY KEY, texture_hash TEXT NOT NULL)");
            statement.execute("CREATE TABLE stat_events(id INTEGER PRIMARY KEY, player_uuid TEXT, stat_key TEXT, previous_value INTEGER, new_value INTEGER, actor TEXT, reason TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE admin_audit(id INTEGER PRIMARY KEY, target_uuid TEXT, target_name TEXT, admin_name TEXT, action TEXT, reason TEXT, created_at TEXT)");

            String[] unsupported = {"player_value_items", "vault_operations", "vault_claims",
                    "vault_cooldowns", "player_value_adjustments", "trade_sessions",
                    "trade_escrow_items", "trade_item_operations", "trade_player_locks",
                    "operation_receipts"};
            for (String table : unsupported) statement.execute("CREATE TABLE " + table + "(id INTEGER)");

            statement.execute("INSERT INTO player_data VALUES ('" + uuid + "','ImportPlayer','2026-11-05',2)");
            statement.execute("INSERT INTO player_stats VALUES ('" + uuid + "',3,1200,99)");
            statement.execute("INSERT INTO player_skins VALUES ('" + uuid + "','ABCDEF0123456789ABCDEF0123456789')");
            statement.execute("INSERT INTO stat_events VALUES (7,'" + uuid + "','official_deaths',2,3,'SERVER','PLAYER_DEATH','2026-11-06 12:00:00')");
            statement.execute("INSERT INTO admin_audit VALUES (11,'" + uuid + "','ImportPlayer','Admin','RESTORE_LIFE','Verified restore','2026-11-06 12:30:00')");
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
