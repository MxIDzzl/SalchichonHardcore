package com.escuadronsalchichon.hardcore.database;

import com.escuadronsalchichon.hardcore.player.PlayerData;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * PostgreSQL implementation of the player persistence contract.
 * Uses short-lived pooled connections; no JDBC connection is shared between
 * Bukkit event threads.
 */
public final class PostgresDatabaseManager implements PlayerDataStore {

    private final JavaPlugin plugin;
    private final PostgresConnectionPool pool;

    public PostgresDatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.pool = new PostgresConnectionPool(plugin);
    }

    @Override
    public boolean connect() {
        try {
            pool.start();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo conectar con PostgreSQL.");
            plugin.getLogger().severe(exception.getMessage());
            return false;
        }
    }

    @Override
    public boolean createTables() {
        try {
            new PostgresSchemaManager(plugin, pool).initialize();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo inicializar el esquema PostgreSQL.");
            exception.printStackTrace();
            return false;
        }
    }

    /**
     * Imports the legacy SQLite file into the already initialized PostgreSQL schema.
     * This is deliberately an explicit maintenance operation, never a startup action.
     */
    public SqliteToPostgresImporter.ImportReport importLegacySqlite() throws SQLException {
        String configuredZone = plugin.getConfig().getString(
                "settings.timezone", "America/Mexico_City");
        final ZoneId zone;
        try {
            zone = ZoneId.of(configuredZone);
        } catch (RuntimeException exception) {
            throw new SQLException("La zona horaria configurada no es válida: " + configuredZone, exception);
        }
        return new SqliteToPostgresImporter(plugin, pool).importDatabase(
                new java.io.File(plugin.getDataFolder(), "hardcore.db"), zone);
    }

    @Override
    public void disconnect() {
        pool.close();
    }

    private boolean isConnected() {
        return pool.isRunning();
    }

    private OffsetDateTime auditTimestamp() {
        String zoneName = plugin.getConfig().getString("settings.timezone", "America/Mexico_City");
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneName);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Zona horaria inválida; auditoría usará UTC.");
            zone = ZoneId.of("UTC");
        }
        return OffsetDateTime.now(zone);
    }

    @Override
    public boolean resetPlayerCounter(UUID uuid, String targetName, String adminName, String reason) {
        return updateLifeAndAudit(uuid, targetName, adminName, reason, "RESET_COUNTER");
    }

    @Override
    public boolean restorePlayerLife(UUID uuid, String targetName, String adminName, String reason) {
        return updateLifeAndAudit(uuid, targetName, adminName, reason, "RESTORE_LIFE");
    }

    private boolean updateLifeAndAudit(UUID uuid, String targetName, String adminName, String reason, String action) {
        if (!isConnected() || uuid == null || blank(targetName) || blank(adminName) || blank(reason)) return false;
        String updateSql = "UPDATE player_data SET last_death_date = NULL WHERE uuid = ?";
        String auditSql = """
                INSERT INTO admin_audit(target_uuid,target_name,admin_name,action,reason,created_at)
                VALUES (?,?,?,?,?,?)
                """;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    update.setObject(1, uuid);
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement audit = connection.prepareStatement(auditSql)) {
                    audit.setObject(1, uuid);
                    audit.setString(2, targetName);
                    audit.setString(3, adminName);
                    audit.setString(4, action);
                    audit.setString(5, reason.trim());
                    audit.setObject(6, auditTimestamp());
                    audit.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo ejecutar la acción administrativa " + action + ".");
            exception.printStackTrace();
            return false;
        }
    }

    @Override
    public void resetDeathsBefore(LocalDate today) {
        if (today == null) throw new IllegalArgumentException("La fecha no puede ser null.");
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE player_data SET last_death_date = NULL WHERE last_death_date < ?")) {
            statement.setObject(1, today);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudieron reiniciar las vidas expiradas.", exception);
        }
    }

    @Override
    public boolean incrementTotems(UUID uuid) {
        if (!isConnected() || uuid == null) return false;
        String sql = """
                INSERT INTO player_data(uuid,username,last_death_date,totem_uses)
                VALUES (?, 'Unknown', NULL, 1)
                ON CONFLICT(uuid) DO UPDATE SET totem_uses = player_data.totem_uses + 1
                """;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo registrar el uso de tótem.");
            exception.printStackTrace();
            return false;
        }
    }

    @Override
    public long getTotems(UUID uuid) {
        if (!isConnected() || uuid == null) return 0L;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT totem_uses FROM player_data WHERE uuid = ?")) {
            statement.setObject(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo consultar el uso de tótems.");
            exception.printStackTrace();
            return 0L;
        }
    }

    @Override
    public boolean savePlayer(PlayerData data) {
        if (!isConnected() || data == null || data.getUuid() == null || blank(data.getUsername())) return false;
        String sql = """
                INSERT INTO player_data(uuid,username,last_death_date)
                VALUES (?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = EXCLUDED.username,
                    last_death_date = EXCLUDED.last_death_date
                """;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, data.getUuid());
            statement.setString(2, data.getUsername());
            if (data.getLastDeathDate() == null) statement.setNull(3, Types.DATE);
            else statement.setObject(3, data.getLastDeathDate());
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudieron guardar los datos del jugador.");
            exception.printStackTrace();
            return false;
        }
    }

    @Override
    public PlayerData getPlayer(UUID uuid) {
        if (uuid == null) throw new IllegalArgumentException("UUID no puede ser null.");
        String sql = "SELECT username,last_death_date FROM player_data WHERE uuid = ?";
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                java.sql.Date date = result.getDate("last_death_date");
                return new PlayerData(uuid, result.getString("username"),
                        date == null ? null : date.toLocalDate());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Error al recuperar los datos del jugador: " + uuid, exception);
        }
    }

    @Override
    public PlayerData getPlayerByName(String username) {
        if (blank(username)) throw new IllegalArgumentException("Nombre no puede estar vacío.");
        String sql = """
                SELECT uuid,username,last_death_date FROM player_data
                WHERE LOWER(username) = LOWER(?) ORDER BY username LIMIT 1
                """;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username.trim());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                java.sql.Date date = result.getDate("last_death_date");
                return new PlayerData(result.getObject("uuid", UUID.class),
                        result.getString("username"), date == null ? null : date.toLocalDate());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Error al buscar al jugador: " + username, exception);
        }
    }

    @Override
    public boolean updateDeathDate(UUID uuid, LocalDate deathDate) {
        if (!isConnected() || uuid == null || deathDate == null) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE player_data SET last_death_date = ? WHERE uuid = ?")) {
            statement.setObject(1, deathDate);
            statement.setObject(2, uuid);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo actualizar la fecha de muerte.");
            exception.printStackTrace();
            return false;
        }
    }


    @Override
    public boolean recordOfficialDeath(UUID uuid, String username, LocalDate deathDate) {
        if (!isConnected() || uuid == null || blank(username) || deathDate == null) return false;
        String playerSql = """
                INSERT INTO player_data(uuid, username, last_death_date, totem_uses)
                VALUES (?, ?, ?, 0)
                ON CONFLICT(uuid) DO UPDATE SET username = EXCLUDED.username, last_death_date = EXCLUDED.last_death_date
                """;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement player = connection.prepareStatement(playerSql)) {
                    player.setObject(1, uuid); player.setString(2, username); player.setObject(3, deathDate); player.executeUpdate();
                }
                long previous = 0;
                try (PreparedStatement query = connection.prepareStatement("SELECT official_deaths FROM player_stats WHERE player_uuid = ? FOR UPDATE")) {
                    query.setObject(1, uuid);
                    try (ResultSet result = query.executeQuery()) { if (result.next()) previous = result.getLong(1); }
                }
                try (PreparedStatement stats = connection.prepareStatement("""
                        INSERT INTO player_stats(player_uuid, official_deaths, updated_at)
                        VALUES (?, 1, now())
                        ON CONFLICT(player_uuid) DO UPDATE SET official_deaths = player_stats.official_deaths + 1, updated_at = now()
                        """)) { stats.setObject(1, uuid); stats.executeUpdate(); }
                try (PreparedStatement event = connection.prepareStatement("""
                        INSERT INTO stat_events(player_uuid, stat_key, previous_value, new_value, actor, reason)
                        VALUES (?, 'official_deaths', ?, ?, 'SERVER', 'PLAYER_DEATH')
                        """)) {
                    event.setObject(1, uuid); event.setLong(2, previous); event.setLong(3, previous + 1); event.executeUpdate();
                }
                connection.commit(); return true;
            } catch (SQLException exception) { connection.rollback(); throw exception; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo registrar la muerte oficial de " + username);
            exception.printStackTrace(); return false;
        }
    }

    @Override
    public boolean addPlaytimeTicks(UUID uuid, String username, long ticks) {
        if (!isConnected() || uuid == null || blank(username) || ticks <= 0) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement player = connection.prepareStatement("""
                        INSERT INTO player_data(uuid, username, last_death_date, totem_uses)
                        VALUES (?, ?, NULL, 0)
                        ON CONFLICT(uuid) DO UPDATE SET username = EXCLUDED.username
                        """)) { player.setObject(1, uuid); player.setString(2, username); player.executeUpdate(); }
                try (PreparedStatement stats = connection.prepareStatement("""
                        INSERT INTO player_stats(player_uuid, playtime_ticks, updated_at)
                        VALUES (?, ?, now())
                        ON CONFLICT(player_uuid) DO UPDATE SET playtime_ticks = player_stats.playtime_ticks + EXCLUDED.playtime_ticks, updated_at = now()
                        """)) { stats.setObject(1, uuid); stats.setLong(2, ticks); stats.executeUpdate(); }
                connection.commit(); return true;
            } catch (SQLException exception) { connection.rollback(); throw exception; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException exception) { plugin.getLogger().severe("No se pudo guardar el tiempo de juego."); exception.printStackTrace(); return false; }
    }

    @Override
    public long getOfficialDeaths(UUID uuid) { return getOfficialStat(uuid, "official_deaths"); }

    @Override
    public long getPlaytimeTicks(UUID uuid) { return getOfficialStat(uuid, "playtime_ticks"); }


    @Override
    public boolean setOfficialStat(UUID uuid, String statKey, long value, String actor, String reason) {
        if (!isConnected() || uuid == null || value < 0 || blank(actor) || blank(reason)) return false;
        String column = switch (statKey == null ? "" : statKey.toLowerCase(java.util.Locale.ROOT)) {
            case "deaths" -> "official_deaths";
            case "playtime" -> "playtime_ticks";
            case "value" -> "player_value";
            default -> null;
        };
        if (column == null) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long previous = 0;
                try (PreparedStatement query = connection.prepareStatement("SELECT " + column + " FROM player_stats WHERE player_uuid = ? FOR UPDATE")) {
                    query.setObject(1, uuid);
                    try (ResultSet result = query.executeQuery()) { if (result.next()) previous = result.getLong(1); }
                }
                try (PreparedStatement upsert = connection.prepareStatement("""
                        INSERT INTO player_stats(player_uuid, official_deaths, playtime_ticks, player_value, updated_at)
                        VALUES (?, 0, 0, 0, now())
                        ON CONFLICT(player_uuid) DO NOTHING
                        """)) { upsert.setObject(1, uuid); upsert.executeUpdate(); }
                try (PreparedStatement update = connection.prepareStatement("UPDATE player_stats SET " + column + " = ?, updated_at = now() WHERE player_uuid = ?")) {
                    update.setLong(1, value); update.setObject(2, uuid);
                    if (update.executeUpdate() != 1) throw new SQLException("No se actualizó la estadística.");
                }
                try (PreparedStatement event = connection.prepareStatement("""
                        INSERT INTO stat_events(player_uuid, stat_key, previous_value, new_value, actor, reason)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    event.setObject(1, uuid); event.setString(2, column); event.setLong(3, previous);
                    event.setLong(4, value); event.setString(5, actor); event.setString(6, reason.trim()); event.executeUpdate();
                }
                connection.commit(); return true;
            } catch (SQLException exception) { connection.rollback(); throw exception; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException exception) { plugin.getLogger().severe("No se pudo corregir la estadística oficial."); exception.printStackTrace(); return false; }
    }

    private long getOfficialStat(UUID uuid, String column) {
        if (!isConnected() || uuid == null) return 0L;
        if (!column.equals("official_deaths") && !column.equals("playtime_ticks")) throw new IllegalArgumentException("Estadística inválida");
        try (Connection connection = pool.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT " + column + " FROM player_stats WHERE player_uuid = ?")) {
            query.setObject(1, uuid);
            try (ResultSet result = query.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        } catch (SQLException exception) { plugin.getLogger().severe("No se pudo consultar " + column); exception.printStackTrace(); return 0L; }
    }


    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
