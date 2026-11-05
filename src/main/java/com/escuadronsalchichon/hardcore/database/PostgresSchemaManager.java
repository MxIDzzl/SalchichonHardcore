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
 * Applies the bundled PostgreSQL schema in a single transaction and records
 * its version. It is intentionally independent from plugin startup for now.
 */
public final class PostgresSchemaManager {

    private static final int CURRENT_SCHEMA_VERSION = 5;

    private final JavaPlugin plugin;
    private final PostgresConnectionPool postgres;

    public PostgresSchemaManager(JavaPlugin plugin, PostgresConnectionPool postgres) {
        this.plugin = plugin;
        this.postgres = postgres;
    }

    public void initialize() throws SQLException {
        try (Connection connection = postgres.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                createVersionTable(connection);
                int installedVersion = getInstalledVersion(connection);
                if (installedVersion > CURRENT_SCHEMA_VERSION) {
                    throw new SQLException("La base PostgreSQL usa una versión de esquema más nueva ("
                            + installedVersion + ") que la soportada por este plugin ("
                            + CURRENT_SCHEMA_VERSION + ").");
                }

                if (installedVersion < 1) {
                    applyStatements(connection, loadStatements("/db/postgresql/schema-v1.sql"));
                    recordVersion(connection, 1);
                    installedVersion = 1;
                }
                if (installedVersion < 2) {
                    applyStatements(connection, loadStatements("/db/postgresql/schema-v2.sql"));
                    recordVersion(connection, 2);
                    installedVersion = 2;
                }
                if (installedVersion < 3) {
                    applyStatements(connection, loadStatements("/db/postgresql/schema-v3.sql"));
                    recordVersion(connection, 3);
                    installedVersion = 3;
                }
                if (installedVersion < 4) {
                    applyStatements(connection, loadStatements("/db/postgresql/schema-v4.sql"));
                    recordVersion(connection, 4);
                    installedVersion = 4;
                }
                if (installedVersion < 5) {
                    applyStatements(connection, loadStatements("/db/postgresql/schema-v5.sql"));
                    recordVersion(connection, 5);
                }

                validateRequiredTables(connection);
                connection.commit();
                plugin.getLogger().info("Esquema PostgreSQL v" + CURRENT_SCHEMA_VERSION + " validado.");
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException restoreException) {
                    // Preserve the original migration failure instead of masking it.
                    if (!connection.isClosed()) {
                        plugin.getLogger().severe(
                                "No se pudo restaurar autoCommit después de inicializar el esquema PostgreSQL."
                        );
                        plugin.getLogger().severe(restoreException.getMessage());
                    }
                }
            }
        }
    }

    private void applyStatements(Connection connection, List<String> statements) throws SQLException {
        for (String sql : statements) {
            try (Statement statement = connection.createStatement()) { statement.execute(sql); }
        }
    }

    private List<String> loadStatements(String resource) throws SQLException {
        InputStream stream = getClass().getResourceAsStream(resource);
        if (stream == null) {
            throw new SQLException("No se encontró el recurso " + resource);
        }

        StringBuilder sql = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                    continue;
                }
                sql.append(line).append('\n');
            }
        } catch (IOException exception) {
            throw new SQLException("No se pudo leer el esquema PostgreSQL.", exception);
        }

        List<String> statements = new ArrayList<>();
        for (String part : sql.toString().split(";")) {
            if (!part.isBlank()) {
                statements.add(part.trim());
            }
        }
        if (statements.isEmpty()) {
            throw new SQLException("El esquema PostgreSQL está vacío.");
        }
        return statements;
    }

    private void createVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
        }
    }

    private int getInstalledVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            result.next();
            return result.getInt(1);
        }
    }

    private void recordVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_version (version) VALUES (?)")) {
            statement.setInt(1, version);
            statement.executeUpdate();
        }
    }

    private void validateRequiredTables(Connection connection) throws SQLException {
        String[] required = {
                "schema_version",
                "player_data",
                "admin_audit",
                "player_stats",
                "stat_events",
                "player_value_items",
                "trade_sessions",
                "trade_escrow_items",
                "operation_receipts",
                "vault_operations",
                "vault_claims",
                "vault_cooldowns",
                "player_value_adjustments",
                "player_skins"
        };
        for (String table : required) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = ?
                    )
                    """)) {
                statement.setString(1, table);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new SQLException("Falta la tabla requerida en PostgreSQL: " + table);
                    }
                }
            }
        }
    }
}
