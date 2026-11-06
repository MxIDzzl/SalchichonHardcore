package com.escuadronsalchichon.hardcore.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the idempotent MySQL baseline. MySQL DDL auto-commits, so every
 * statement is retryable and the version is recorded only after validation.
 */
public final class MySqlSchemaManager {
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final String RESOURCE = "/db/mysql/schema-v1.sql";

    private final JavaPlugin plugin;
    private final MySqlConnectionPool pool;

    public MySqlSchemaManager(JavaPlugin plugin, MySqlConnectionPool pool) {
        this.plugin = plugin;
        this.pool = pool;
    }

    public void initialize() throws SQLException {
        try (Connection connection = pool.getConnection()) {
            createVersionTable(connection);
            int installed = installedVersion(connection);
            if (installed > CURRENT_SCHEMA_VERSION) {
                throw new SQLException("La base MySQL usa una versión de esquema más nueva que este plugin.");
            }
            if (installed < 1) {
                apply(connection, loadStatements());
                validateRequiredTables(connection);
                recordVersion(connection, 1);
            } else {
                validateRequiredTables(connection);
            }
            plugin.getLogger().info("Esquema MySQL v" + CURRENT_SCHEMA_VERSION + " validado.");
        }
    }

    private void createVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INT NOT NULL PRIMARY KEY,
                        applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        }
    }

    private int installedVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void recordVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_version(version) VALUES (?)")) {
            statement.setInt(1, version);
            statement.executeUpdate();
        }
    }

    private void apply(Connection connection, List<String> statements) throws SQLException {
        for (String sql : statements) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private List<String> loadStatements() throws SQLException {
        InputStream stream = getClass().getResourceAsStream(RESOURCE);
        if (stream == null) throw new SQLException("No se encontró el recurso " + RESOURCE);
        StringBuilder source = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("--")) source.append(line).append('\n');
            }
        } catch (IOException exception) {
            throw new SQLException("No se pudo leer el esquema MySQL.", exception);
        }
        List<String> result = new ArrayList<>();
        for (String part : source.toString().split(";")) {
            if (!part.isBlank()) result.add(part.trim());
        }
        if (result.isEmpty()) throw new SQLException("El esquema MySQL está vacío.");
        return result;
    }

    private void validateRequiredTables(Connection connection) throws SQLException {
        String[] tables = {
                "schema_version", "player_data", "admin_audit", "player_stats", "stat_events",
                "player_value_items", "trade_sessions", "trade_escrow_items", "trade_item_operations",
                "trade_player_locks", "operation_receipts", "vault_operations", "vault_claims",
                "vault_cooldowns", "player_value_adjustments", "player_skins", "public_clans",
                "player_clan_memberships"
        };
        for (String table : tables) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
                statement.setString(1, table);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getLong(1) != 1) {
                        throw new SQLException("Falta la tabla requerida en MySQL: " + table);
                    }
                }
            }
        }
    }
}
