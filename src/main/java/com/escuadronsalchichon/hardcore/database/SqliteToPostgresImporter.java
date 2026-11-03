package com.escuadronsalchichon.hardcore.database;

import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * One-way, transactional importer for the legacy SQLite database.
 *
 * Safety contract:
 * - The source file is opened read-only and is never modified.
 * - PostgreSQL destination tables must be empty.
 * - Any error rolls back the complete PostgreSQL import.
 * - This class does not switch the live plugin away from SQLite.
 */
public final class SqliteToPostgresImporter {

    private final JavaPlugin plugin;
    private final PostgresConnectionPool postgres;

    public SqliteToPostgresImporter(JavaPlugin plugin, PostgresConnectionPool postgres) {
        this.plugin = plugin;
        this.postgres = postgres;
    }

    public ImportReport importDatabase(File sqliteFile, ZoneId legacyTimezone) throws SQLException {
        if (sqliteFile == null || !sqliteFile.isFile() || !sqliteFile.canRead()) {
            throw new SQLException("No se encuentra el archivo SQLite o no se puede leer.");
        }
        if (legacyTimezone == null) {
            throw new IllegalArgumentException("La zona horaria de los datos heredados es obligatoria.");
        }

        // Configure Xerial read-only mode explicitly; do not append URI parameters to the JDBC path.
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setReadOnly(true);
        sqliteConfig.setBusyTimeout(5000);

        String sqliteUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
        try (Connection source = DriverManager.getConnection(sqliteUrl, sqliteConfig.toProperties());
             Connection destination = postgres.getConnection()) {

            verifySqliteIntegrity(source);
            verifySourceSchema(source);
            ensureDestinationEmpty(destination);

            boolean originalAutoCommit = destination.getAutoCommit();
            destination.setAutoCommit(false);
            try {
                long players = importPlayers(source, destination);
                long audits = importAudit(source, destination, legacyTimezone);
                resetAuditIdentity(destination);

                long destinationPlayers = count(destination, "player_data");
                long destinationAudits = count(destination, "admin_audit");
                if (players != destinationPlayers || audits != destinationAudits) {
                    throw new SQLException("La verificación de conteos no coincide; se canceló la importación.");
                }

                destination.commit();
                plugin.getLogger().info(
                        "Importación SQLite validada: " + players + " jugadores y "
                                + audits + " registros administrativos."
                );
                return new ImportReport(players, audits);
            } catch (SQLException | RuntimeException exception) {
                try {
                    destination.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            } finally {
                try {
                    destination.setAutoCommit(originalAutoCommit);
                } catch (SQLException exception) {
                    plugin.getLogger().warning("No se pudo restaurar autocommit después de la importación.");
                }
            }
        }
    }

    private void verifySqliteIntegrity(Connection source) throws SQLException {
        try (Statement statement = source.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("PRAGMA integrity_check no devolvió 'ok'.");
            }
        }
    }

    private void verifySourceSchema(Connection source) throws SQLException {
        requireColumns(source, "player_data", "uuid", "username", "last_death_date", "totem_uses");
        requireColumns(source, "admin_audit", "id", "target_uuid", "target_name",
                "admin_name", "action", "reason", "created_at");
    }

    private void requireColumns(Connection connection, String table, String... required) throws SQLException {
        java.util.Set<String> found = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                found.add(result.getString("name").toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (String column : required) {
            if (!found.contains(column.toLowerCase(java.util.Locale.ROOT))) {
                throw new SQLException("Falta la columna SQLite requerida: " + table + "." + column);
            }
        }
    }

    private void ensureDestinationEmpty(Connection destination) throws SQLException {
        if (count(destination, "player_data") != 0 || count(destination, "admin_audit") != 0) {
            throw new SQLException("La base PostgreSQL destino no está vacía; no se importaron datos.");
        }
    }

    private long importPlayers(Connection source, Connection destination) throws SQLException {
        String select = "SELECT uuid, username, last_death_date, totem_uses FROM player_data ORDER BY uuid";
        String insert = """
                INSERT INTO player_data (uuid, username, last_death_date, totem_uses)
                VALUES (?, ?, ?, ?)
                """;
        long count = 0;
        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery(select);
             PreparedStatement write = destination.prepareStatement(insert)) {
            while (rows.next()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(rows.getString("uuid"));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("UUID inválido en player_data, fila " + (count + 1), exception);
                }
                String username = rows.getString("username");
                if (username == null || username.isBlank() || username.length() > 16) {
                    throw new SQLException("Nombre de jugador inválido para UUID " + uuid);
                }
                String deathDate = rows.getString("last_death_date");
                write.setObject(1, uuid);
                write.setString(2, username);
                if (deathDate == null || deathDate.isBlank()) {
                    write.setObject(3, null);
                } else {
                    try {
                        write.setObject(3, LocalDate.parse(deathDate));
                    } catch (DateTimeParseException exception) {
                        throw new SQLException("Fecha de muerte inválida para UUID " + uuid, exception);
                    }
                }
                long totems = rows.getLong("totem_uses");
                if (totems < 0) {
                    throw new SQLException("Contador de tótems negativo para UUID " + uuid);
                }
                write.setLong(4, totems);
                write.addBatch();
                count++;
                if (count % 500 == 0) write.executeBatch();
            }
            write.executeBatch();
        }
        return count;
    }

    private long importAudit(Connection source, Connection destination, ZoneId timezone) throws SQLException {
        String select = """
                SELECT id, target_uuid, target_name, admin_name, action, reason, created_at
                FROM admin_audit ORDER BY id
                """;
        String insert = """
                INSERT INTO admin_audit
                    (id, target_uuid, target_name, admin_name, action, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        long count = 0;
        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery(select);
             PreparedStatement write = destination.prepareStatement(insert)) {
            while (rows.next()) {
                UUID targetUuid;
                try {
                    targetUuid = UUID.fromString(rows.getString("target_uuid"));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("UUID inválido en admin_audit, ID " + rows.getLong("id"), exception);
                }
                String timestamp = rows.getString("created_at");
                if (timestamp == null || timestamp.isBlank()) {
                    throw new SQLException("Timestamp vacío en admin_audit, ID " + rows.getLong("id"));
                }
                LocalDateTime localTimestamp;
                try {
                    localTimestamp = LocalDateTime.parse(timestamp);
                } catch (DateTimeParseException exception) {
                    throw new SQLException("Timestamp no reconocido en admin_audit, ID "
                            + rows.getLong("id") + ". Se requiere revisión manual.", exception);
                }

                write.setLong(1, rows.getLong("id"));
                write.setObject(2, targetUuid);
                write.setString(3, rows.getString("target_name"));
                write.setString(4, rows.getString("admin_name"));
                write.setString(5, rows.getString("action"));
                write.setString(6, rows.getString("reason"));
                write.setObject(7, localTimestamp.atZone(timezone).toOffsetDateTime());
                write.addBatch();
                count++;
                if (count % 500 == 0) write.executeBatch();
            }
            write.executeBatch();
        }
        return count;
    }

    private void resetAuditIdentity(Connection destination) throws SQLException {
        try (Statement statement = destination.createStatement()) {
            statement.execute("""
                    SELECT setval(
                        pg_get_serial_sequence('admin_audit', 'id'),
                        COALESCE((SELECT MAX(id) FROM admin_audit), 1),
                        EXISTS (SELECT 1 FROM admin_audit)
                    )
                    """);
        }
    }

    private long count(Connection connection, String table) throws SQLException {
        // Table names are private constants at call sites, never user input.
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    public record ImportReport(long playersImported, long auditRowsImported) { }
}
