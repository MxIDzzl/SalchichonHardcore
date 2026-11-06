package com.escuadronsalchichon.hardcore.database;

import com.escuadronsalchichon.hardcore.player.PlayerData;
import com.escuadronsalchichon.hardcore.trade.TradeEscrowItem;
import com.escuadronsalchichon.hardcore.trade.TradeEscrowRecord;
import com.escuadronsalchichon.hardcore.trade.TradeSessionRecord;
import com.escuadronsalchichon.hardcore.trade.TradeStatus;
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
                VALUES (?, ?, NULL, 0)
                ON CONFLICT(uuid) DO UPDATE SET username = EXCLUDED.username
                """;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement player = connection.prepareStatement(playerSql)) {
                    player.setObject(1, uuid); player.setString(2, username); player.executeUpdate();
                }
                LocalDate previousDeathDate;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT last_death_date FROM player_data WHERE uuid = ? FOR UPDATE")) {
                    query.setObject(1, uuid);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) throw new SQLException("No se encontró el jugador después del upsert.");
                        previousDeathDate = result.getObject(1, LocalDate.class);
                    }
                }
                // Idempotencia por día: reintentar el mismo evento no duplica la muerte.
                // Una restauración administrativa limpia last_death_date y permite otra muerte real.
                if (deathDate.equals(previousDeathDate)) {
                    connection.commit();
                    return true;
                }
                try (PreparedStatement updateDeath = connection.prepareStatement(
                        "UPDATE player_data SET last_death_date = ? WHERE uuid = ?")) {
                    updateDeath.setObject(1, deathDate);
                    updateDeath.setObject(2, uuid);
                    if (updateDeath.executeUpdate() != 1) throw new SQLException("No se pudo actualizar la fecha de muerte.");
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
                if ("player_value".equals(column)) {
                    try (PreparedStatement adjustment = connection.prepareStatement("""
                            INSERT INTO player_value_adjustments(
                                player_uuid, previous_value, new_value, adjustment_delta, actor, reason
                            ) VALUES (?, ?, ?, ?, ?, ?)
                            """)) {
                        adjustment.setObject(1, uuid);
                        adjustment.setLong(2, previous);
                        adjustment.setLong(3, value);
                        adjustment.setLong(4, Math.subtractExact(value, previous));
                        adjustment.setString(5, actor);
                        adjustment.setString(6, reason.trim());
                        adjustment.executeUpdate();
                    }
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



    @Override
    public boolean prepareVaultDeposit(UUID uuid, String username, VaultItemRecord item) {
        if (!isConnected() || uuid == null || blank(username) || item == null || !uuid.equals(item.playerUuid())) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement player = connection.prepareStatement("""
                        INSERT INTO player_data(uuid,username,last_death_date,totem_uses)
                        VALUES (?,?,NULL,0) ON CONFLICT(uuid) DO UPDATE SET username=EXCLUDED.username
                        """)) {
                    player.setObject(1, uuid); player.setString(2, username); player.executeUpdate();
                }
                try (PreparedStatement operation = connection.prepareStatement("""
                        INSERT INTO vault_operations(
                            operation_id,player_uuid,operation_type,status,item_id,material,amount,
                            item_payload,credited_value,created_at,updated_at
                        ) VALUES (?,?,'DEPOSIT','PREPARED',?,?,?,?,?,?,?)
                        """)) {
                    operation.setObject(1, item.itemId()); operation.setObject(2, uuid);
                    operation.setObject(3, item.itemId()); operation.setString(4, item.material());
                    operation.setInt(5, item.amount()); operation.setBytes(6, item.payload());
                    operation.setLong(7, item.creditedValue());
                    OffsetDateTime created=OffsetDateTime.ofInstant(item.createdAt(), ZoneId.of("UTC"));
                    operation.setObject(8, created); operation.setObject(9, created); operation.executeUpdate();
                }
                connection.commit(); return true;
            } catch (SQLException exception) { connection.rollback(); throw exception; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo preparar el depósito Player Value."); exception.printStackTrace(); return false;
        }
    }

    @Override
    public boolean commitVaultDeposit(UUID uuid, UUID operationId) {
        if (!isConnected() || uuid == null || operationId == null) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String material; int amount; byte[] payload; long value; OffsetDateTime created;
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT material,amount,item_payload,credited_value,created_at,status
                        FROM vault_operations
                        WHERE operation_id=? AND player_uuid=? AND operation_type='DEPOSIT'
                        FOR UPDATE
                        """)) {
                    query.setObject(1, operationId); query.setObject(2, uuid);
                    try (ResultSet row = query.executeQuery()) {
                        if (!row.next()) { connection.rollback(); return false; }
                        if ("COMMITTED".equals(row.getString("status"))) { connection.commit(); return true; }
                        if (!"DELIVERY_PENDING".equals(row.getString("status"))) { connection.rollback(); return false; }
                        material=row.getString("material"); amount=row.getInt("amount"); payload=row.getBytes("item_payload");
                        value=row.getLong("credited_value"); created=row.getObject("created_at", OffsetDateTime.class);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO player_value_items(item_id,player_uuid,material,amount,item_payload,credited_value,created_at)
                        VALUES (?,?,?,?,?,?,?)
                        """)) {
                    insert.setObject(1, operationId); insert.setObject(2, uuid); insert.setString(3, material);
                    insert.setInt(4, amount); insert.setBytes(5, payload); insert.setLong(6, value);
                    insert.setObject(7, created); insert.executeUpdate();
                }
                try (PreparedStatement stats = connection.prepareStatement("""
                        INSERT INTO player_stats(player_uuid,player_value,updated_at) VALUES (?,?,now())
                        ON CONFLICT(player_uuid) DO UPDATE SET player_value=player_stats.player_value+EXCLUDED.player_value,
                        updated_at=now()
                        WHERE player_stats.player_value <= 9223372036854775807 - EXCLUDED.player_value
                        """)) {
                    stats.setObject(1, uuid); stats.setLong(2, value);
                    if (stats.executeUpdate() != 1) throw new SQLException("El Player Value excedería el límite BIGINT.");
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE vault_operations SET status='COMMITTED',updated_at=now()
                        WHERE operation_id=? AND player_uuid=? AND status='DELIVERY_PENDING'
                        """)) {
                    update.setObject(1, operationId); update.setObject(2, uuid);
                    if (update.executeUpdate()!=1) throw new SQLException("La operación dejó de estar PREPARED.");
                }
                connection.commit(); return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo confirmar el depósito Player Value."); exception.printStackTrace(); return false;
        }
    }


    @Override
    public java.util.List<VaultDepositRecoveryRecord> getUncertainVaultDeposits(UUID uuid) {
        if (!isConnected() || uuid == null) return null;
        java.util.List<VaultDepositRecoveryRecord> items = new java.util.ArrayList<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT operation_id,material,amount,item_payload,credited_value,created_at,status
                     FROM vault_operations
                     WHERE player_uuid=? AND operation_type='DEPOSIT'
                       AND status IN ('PREPARED','DELIVERY_PENDING','RECOVERY_REQUIRED')
                     ORDER BY created_at,operation_id
                     """)) {
            query.setObject(1, uuid);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    OffsetDateTime created = rows.getObject("created_at", OffsetDateTime.class);
                    VaultItemRecord item = new VaultItemRecord(rows.getObject("operation_id", UUID.class), uuid,
                            rows.getString("material"), rows.getInt("amount"), rows.getBytes("item_payload"),
                            rows.getLong("credited_value"), created.toInstant());
                    items.add(new VaultDepositRecoveryRecord(item, rows.getString("status")));
                }
            }
            return java.util.List.copyOf(items);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().severe("No se pudieron consultar depósitos Player Value en recuperación.");
            exception.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean markVaultDepositInventoryRemoved(UUID uuid, UUID operationId) {
        if (!isConnected() || uuid == null || operationId == null) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE vault_operations SET status='DELIVERY_PENDING',updated_at=now()
                     WHERE operation_id=? AND player_uuid=? AND operation_type='DEPOSIT' AND status='PREPARED'
                     """)) {
            update.setObject(1, operationId);
            update.setObject(2, uuid);
            return update.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo registrar la eliminación del objeto del inventario.");
            exception.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean abortVaultDeposit(UUID uuid, UUID operationId) {
        if (!isConnected() || uuid == null || operationId == null) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE vault_operations SET status='CANCELLED',updated_at=now()
                     WHERE operation_id=? AND player_uuid=? AND operation_type='DEPOSIT'
                       AND status IN ('PREPARED','RECOVERY_REQUIRED')
                     """)) {
            update.setObject(1, operationId);
            update.setObject(2, uuid);
            return update.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo abortar el depósito recuperado.");
            exception.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean depositVaultItem(UUID uuid, String username, VaultItemRecord item) {
        if (!isConnected() || uuid == null || blank(username) || item == null
                || !uuid.equals(item.playerUuid()) || item.amount() <= 0 || item.creditedValue() < 0) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement player = connection.prepareStatement("""
                        INSERT INTO player_data(uuid,username,last_death_date,totem_uses)
                        VALUES (?,?,NULL,0) ON CONFLICT(uuid) DO UPDATE SET username=EXCLUDED.username
                        """)) {
                    player.setObject(1, uuid); player.setString(2, username); player.executeUpdate();
                }
                try (PreparedStatement operation = connection.prepareStatement("""
                        INSERT INTO vault_operations(
                            operation_id, player_uuid, operation_type, status, item_id,
                            material, amount, item_payload, credited_value, created_at, updated_at
                        ) VALUES (?, ?, 'DEPOSIT', 'COMMITTED', ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    operation.setObject(1, item.itemId());
                    operation.setObject(2, uuid);
                    operation.setObject(3, item.itemId());
                    operation.setString(4, item.material());
                    operation.setInt(5, item.amount());
                    operation.setBytes(6, item.payload());
                    operation.setLong(7, item.creditedValue());
                    operation.setObject(8, OffsetDateTime.ofInstant(item.createdAt(), ZoneId.of("UTC")));
                    operation.setObject(9, OffsetDateTime.ofInstant(item.createdAt(), ZoneId.of("UTC")));
                    operation.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO player_value_items(item_id,player_uuid,material,amount,item_payload,credited_value,created_at)
                        VALUES (?,?,?,?,?,?,?)
                        """)) {
                    insert.setObject(1, item.itemId()); insert.setObject(2, uuid); insert.setString(3, item.material());
                    insert.setInt(4, item.amount()); insert.setBytes(5, item.payload()); insert.setLong(6, item.creditedValue());
                    insert.setObject(7, OffsetDateTime.ofInstant(item.createdAt(), ZoneId.of("UTC"))); insert.executeUpdate();
                }
                try (PreparedStatement stats = connection.prepareStatement("""
                        INSERT INTO player_stats(player_uuid,player_value,updated_at) VALUES (?,?,now())
                        ON CONFLICT(player_uuid) DO UPDATE SET player_value=player_stats.player_value+EXCLUDED.player_value,
                        updated_at=now()
                        """)) {
                    stats.setObject(1, uuid); stats.setLong(2, item.creditedValue()); stats.executeUpdate();
                }
                connection.commit(); return true;
            } catch (SQLException exception) { connection.rollback(); throw exception; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo depositar el objeto en Player Value."); exception.printStackTrace(); return false;
        }
    }

    @Override
    public java.util.List<VaultItemRecord> getVaultItems(UUID uuid, int limit, int offset) {
        if (!isConnected() || uuid == null || limit < 1 || offset < 0) return java.util.List.of();
        java.util.List<VaultItemRecord> items = new java.util.ArrayList<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT item_id,material,amount,item_payload,credited_value,created_at
                     FROM player_value_items WHERE player_uuid=? ORDER BY created_at,item_id LIMIT ? OFFSET ?
                     """)) {
            query.setObject(1, uuid); query.setInt(2, limit); query.setInt(3, offset);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    OffsetDateTime created = rows.getObject("created_at", OffsetDateTime.class);
                    items.add(new VaultItemRecord(rows.getObject("item_id", UUID.class), uuid,
                            rows.getString("material"), rows.getInt("amount"), rows.getBytes("item_payload"),
                            rows.getLong("credited_value"), created.toInstant()));
                }
            }
            return java.util.List.copyOf(items);
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo consultar la bóveda Player Value."); exception.printStackTrace();
            return java.util.List.of();
        }
    }

    @Override
    public long getPlayerValue(UUID uuid) {
        if (!isConnected() || uuid == null) return 0L;
        try (Connection connection = pool.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT player_value FROM player_stats WHERE player_uuid=?")) {
            query.setObject(1, uuid);
            try (ResultSet result = query.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        } catch (SQLException exception) { plugin.getLogger().severe("No se pudo consultar Player Value."); return 0L; }
    }

    @Override
    public boolean setVaultCooldown(UUID uuid, java.time.Instant nextAllowedAt) {
        if (!isConnected() || uuid == null || nextAllowedAt == null) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO vault_cooldowns(player_uuid,next_allowed_at) VALUES (?,?)
                     ON CONFLICT(player_uuid) DO UPDATE SET next_allowed_at=EXCLUDED.next_allowed_at
                     """)) {
            statement.setObject(1, uuid);
            statement.setObject(2, OffsetDateTime.ofInstant(nextAllowedAt, ZoneId.of("UTC")));
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) { plugin.getLogger().severe("No se pudo guardar el cooldown de la bóveda."); return false; }
    }

    @Override
    public java.time.Instant getVaultCooldown(UUID uuid) {
        if (!isConnected() || uuid == null) return java.time.Instant.EPOCH;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT next_allowed_at FROM vault_cooldowns WHERE player_uuid=?")) {
            statement.setObject(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject(1, OffsetDateTime.class).toInstant() : java.time.Instant.EPOCH;
            }
        } catch (SQLException exception) { plugin.getLogger().severe("No se pudo leer el cooldown de la bóveda."); return java.time.Instant.EPOCH; }
    }

    @Override
    public VaultClaimRecord createVaultWithdrawal(
            UUID uuid, UUID itemId, java.time.Instant now, java.time.Instant nextAllowedAt
    ) {
        if (!isConnected() || uuid == null || itemId == null || now == null
                || nextAllowedAt == null || nextAllowedAt.isBefore(now)) return null;

        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Lock the durable owner row first. A SELECT ... FOR UPDATE on the
                // cooldown table alone does not lock anything when no cooldown row
                // exists yet, allowing two first-time withdrawals to race.
                try (PreparedStatement owner = connection.prepareStatement(
                        "SELECT uuid FROM player_data WHERE uuid=? FOR UPDATE")) {
                    owner.setObject(1, uuid);
                    try (ResultSet result = owner.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return null;
                        }
                    }
                }

                try (PreparedStatement cooldown = connection.prepareStatement(
                        "SELECT next_allowed_at FROM vault_cooldowns WHERE player_uuid=? FOR UPDATE")) {
                    cooldown.setObject(1, uuid);
                    try (ResultSet result = cooldown.executeQuery()) {
                        if (result.next()) {
                            java.time.Instant allowedAt = result.getObject(1, OffsetDateTime.class).toInstant();
                            if (now.isBefore(allowedAt)) {
                                connection.rollback();
                                return null;
                            }
                        }
                    }
                }

                String material;
                int amount;
                byte[] payload;
                long value;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT material,amount,item_payload,credited_value FROM player_value_items WHERE item_id=? AND player_uuid=? FOR UPDATE")) {
                    query.setObject(1, itemId);
                    query.setObject(2, uuid);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return null;
                        }
                        material = result.getString(1);
                        amount = result.getInt(2);
                        payload = result.getBytes(3);
                        value = result.getLong(4);
                    }
                }

                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM player_value_items WHERE item_id=? AND player_uuid=?")) {
                    delete.setObject(1, itemId);
                    delete.setObject(2, uuid);
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return null;
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE player_stats SET player_value=player_value-?,updated_at=now() WHERE player_uuid=? AND player_value>=?")) {
                    update.setLong(1, value);
                    update.setObject(2, uuid);
                    update.setLong(3, value);
                    if (update.executeUpdate() != 1) throw new SQLException("Player Value desincronizado");
                }

                UUID operationId = UUID.randomUUID();
                UUID claimId = UUID.randomUUID();
                try (PreparedStatement operation = connection.prepareStatement(
                        "INSERT INTO vault_operations(operation_id,player_uuid,operation_type,status,item_id,material,amount,item_payload,credited_value) VALUES (?,?,'WITHDRAW','DELIVERY_PENDING',?,?,?,?,?)")) {
                    operation.setObject(1, operationId);
                    operation.setObject(2, uuid);
                    operation.setObject(3, itemId);
                    operation.setString(4, material);
                    operation.setInt(5, amount);
                    operation.setBytes(6, payload);
                    operation.setLong(7, value);
                    operation.executeUpdate();
                }
                try (PreparedStatement claim = connection.prepareStatement(
                        "INSERT INTO vault_claims(claim_id,operation_id,player_uuid,item_payload,material,amount,status) VALUES (?,?,?,?,?,?,'PENDING')")) {
                    claim.setObject(1, claimId);
                    claim.setObject(2, operationId);
                    claim.setObject(3, uuid);
                    claim.setBytes(4, payload);
                    claim.setString(5, material);
                    claim.setInt(6, amount);
                    claim.executeUpdate();
                }
                try (PreparedStatement cooldown = connection.prepareStatement("""
                        INSERT INTO vault_cooldowns(player_uuid,next_allowed_at) VALUES (?,?)
                        ON CONFLICT(player_uuid) DO UPDATE SET next_allowed_at=EXCLUDED.next_allowed_at
                        """)) {
                    cooldown.setObject(1, uuid);
                    cooldown.setObject(2, OffsetDateTime.ofInstant(nextAllowedAt, ZoneId.of("UTC")));
                    cooldown.executeUpdate();
                }

                connection.commit();
                return new VaultClaimRecord(claimId, operationId, uuid, material, amount, payload, "PENDING");
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException | java.time.DateTimeException exception) {
            plugin.getLogger().severe("No se pudo crear el retiro de Player Value.");
            exception.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean markVaultClaimDelivering(UUID claimId, UUID playerUuid) {
        if (!isConnected() || claimId == null || playerUuid == null) return false;
        String sql = """
                UPDATE vault_claims
                SET status='DELIVERING'
                WHERE claim_id=? AND player_uuid=? AND status='PENDING'
                  AND EXISTS (
                      SELECT 1 FROM vault_operations
                      WHERE vault_operations.operation_id=vault_claims.operation_id
                        AND vault_operations.player_uuid=? AND vault_operations.status='DELIVERY_PENDING'
                  )
                """;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, claimId);
            statement.setObject(2, playerUuid);
            statement.setObject(3, playerUuid);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo iniciar la entrega del retiro.");
            return false;
        }
    }

    @Override
    public boolean markVaultClaimDelivered(UUID claimId, UUID playerUuid) {
        if (!isConnected() || claimId == null || playerUuid == null) return false;
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID operationId;
                try (PreparedStatement update = c.prepareStatement("""
                        UPDATE vault_claims SET status='DELIVERED', delivered_at=now()
                        WHERE claim_id=? AND player_uuid=? AND status IN ('DELIVERING','RECOVERY_REQUIRED')
                        RETURNING operation_id
                        """)) {
                    update.setObject(1, claimId);
                    update.setObject(2, playerUuid);
                    try (ResultSet result = update.executeQuery()) {
                        if (!result.next()) { c.rollback(); return false; }
                        operationId = result.getObject(1, UUID.class);
                    }
                }
                try (PreparedStatement operation = c.prepareStatement("""
                        UPDATE vault_operations SET status='DELIVERED', updated_at=now()
                        WHERE operation_id=? AND status IN ('DELIVERY_PENDING','RECOVERY_REQUIRED')
                        """)) {
                    operation.setObject(1, operationId);
                    if (operation.executeUpdate() != 1) throw new SQLException("Estado de operación inconsistente");
                }
                c.commit();
                return true;
            } catch (SQLException exception) {
                c.rollback();
                throw exception;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo cerrar atómicamente el retiro.");
            exception.printStackTrace();
            return false;
        }
    }


    @Override
    public java.util.List<VaultClaimRecord> getPendingVaultClaims(UUID playerUuid){
        if(!isConnected()||playerUuid==null)return java.util.List.of();
        java.util.List<VaultClaimRecord> out=new java.util.ArrayList<>();
        try(Connection c=pool.getConnection();PreparedStatement s=c.prepareStatement("SELECT claim_id,operation_id,item_payload,material,amount,status FROM vault_claims WHERE player_uuid=? AND status='PENDING' ORDER BY created_at")){
            s.setObject(1,playerUuid);try(ResultSet r=s.executeQuery()){while(r.next())out.add(new VaultClaimRecord(r.getObject(1,UUID.class),r.getObject(2,UUID.class),playerUuid,r.getString(4),r.getInt(5),r.getBytes(3),r.getString(6)));}return java.util.List.copyOf(out);
        }catch(SQLException e){plugin.getLogger().severe("No se pudieron consultar retiros pendientes.");return java.util.List.of();}
    }



    @Override
    public java.util.List<VaultClaimRecord> getUncertainVaultClaims(UUID playerUuid) {
        if (!isConnected() || playerUuid == null) return null;
        java.util.List<VaultClaimRecord> claims = new java.util.ArrayList<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT claim_id,operation_id,item_payload,material,amount,status
                     FROM vault_claims
                     WHERE player_uuid=? AND status IN ('PENDING','DELIVERING','RECOVERY_REQUIRED')
                     ORDER BY created_at,claim_id
                     """)) {
            query.setObject(1, playerUuid);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) claims.add(new VaultClaimRecord(
                        rows.getObject("claim_id", UUID.class),
                        rows.getObject("operation_id", UUID.class), playerUuid,
                        rows.getString("material"), rows.getInt("amount"),
                        rows.getBytes("item_payload"), rows.getString("status")));
            }
            return java.util.List.copyOf(claims);
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudieron consultar reclamaciones de retiro inciertas.");
            exception.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean updatePlayerSkin(UUID uuid, String textureHash) {
        if (!isConnected() || uuid == null) return false;
        if (textureHash == null) {
            try (Connection connection = pool.getConnection();
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM player_skins WHERE player_uuid=?")) {
                delete.setObject(1, uuid);
                return delete.executeUpdate() >= 0;
            } catch (SQLException exception) {
                plugin.getLogger().warning("No se pudo limpiar la skin registrada para " + uuid + ".");
                return false;
            }
        }
        if (!textureHash.matches("(?i)[0-9a-f]{32,64}")) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_skins(player_uuid,texture_hash,updated_at)
                VALUES (?,?,now())
                ON CONFLICT(player_uuid) DO UPDATE SET
                    texture_hash=EXCLUDED.texture_hash,
                    updated_at=now()
                """)) {
            statement.setObject(1, uuid);
            statement.setString(2, textureHash.toLowerCase(java.util.Locale.ROOT));
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().warning("No se pudo actualizar la skin registrada para " + uuid + ".");
            return false;
        }
    }

    @Override
    public VaultRecoverySummary getVaultRecoverySummary() {
        if (!isConnected()) return null;
        String sql = """
                SELECT
                    COUNT(*) FILTER (WHERE operation_type='DEPOSIT' AND status IN ('PREPARED','DELIVERY_PENDING')),
                    COUNT(*) FILTER (WHERE operation_type='WITHDRAW' AND status='DELIVERY_PENDING'),
                    COUNT(*) FILTER (WHERE operation_type='DEPOSIT' AND status='RECOVERY_REQUIRED'),
                    COUNT(*) FILTER (WHERE operation_type='WITHDRAW' AND status='RECOVERY_REQUIRED')
                FROM vault_operations
                """;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) return null;
            return new VaultRecoverySummary(result.getInt(1), result.getInt(2),
                    result.getInt(3), result.getInt(4));
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo consultar el estado de recuperación de Player Value.");
            exception.printStackTrace();
            return null;
        }
    }

    @Override
    public int flagUncertainVaultOperations() {
        if (!isConnected()) return -1;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement claims = connection.prepareStatement(
                        "UPDATE vault_claims SET status='RECOVERY_REQUIRED' WHERE status='DELIVERING'")) {
                    changed = claims.executeUpdate();
                }
                try (PreparedStatement operations = connection.prepareStatement(
                        "UPDATE vault_operations SET status='RECOVERY_REQUIRED',updated_at=now() WHERE status='DELIVERY_PENDING' AND operation_type='WITHDRAW' AND operation_id IN (SELECT operation_id FROM vault_claims WHERE status='RECOVERY_REQUIRED')")) {
                    operations.executeUpdate();
                }
                // A PREPARED deposit may have stopped before or after inventory
                // mutation. Quarantine it until an inventory-aware recovery can prove its state.
                try (PreparedStatement deposits = connection.prepareStatement(
                        "UPDATE vault_operations SET status='RECOVERY_REQUIRED',updated_at=now() WHERE operation_type='DEPOSIT' AND status='PREPARED'")) {
                    changed += deposits.executeUpdate();
                }
                connection.commit();
                return changed;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudieron marcar reclamaciones inciertas para recuperación.");
            exception.printStackTrace();
            return -1;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }


    @Override
    public TradeSessionRecord getActiveTrade(UUID playerUuid) {
        if (!isConnected() || playerUuid == null) return null;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT trade_id,player_a,player_b,status,revision,
                            confirmed_revision_a,confirmed_revision_b
                     FROM trade_sessions
                     WHERE (player_a=? OR player_b=?)
                       AND (status IN ('OPEN','LOCKED','RECOVERY_REQUIRED')
                            OR (status='EXPIRED' AND EXISTS (
                                SELECT 1 FROM trade_player_locks l
                                WHERE l.trade_id=trade_sessions.trade_id AND l.player_uuid=?
                            )))
                     ORDER BY created_at DESC LIMIT 1
                     """)) {
            statement.setObject(1, playerUuid);
            statement.setObject(2, playerUuid);
            statement.setObject(3, playerUuid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new TradeSessionRecord(
                        result.getObject("trade_id", UUID.class),
                        result.getObject("player_a", UUID.class),
                        result.getObject("player_b", UUID.class),
                        TradeStatus.valueOf(result.getString("status")),
                        result.getLong("revision"),
                        (Long) result.getObject("confirmed_revision_a"),
                        (Long) result.getObject("confirmed_revision_b"));
            }
        } catch (SQLException | IllegalArgumentException exception) {
            plugin.getLogger().severe("No se pudo consultar el intercambio activo.");
            return null;
        }
    }

    @Override
    public java.util.List<TradeEscrowRecord> getTradeItems(UUID tradeId) {
        if (!isConnected() || tradeId == null) return java.util.List.of();
        java.util.List<TradeEscrowRecord> items = new java.util.ArrayList<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT operation_id,trade_id,owner_uuid,recipient_uuid,item_payload,
                            item_fingerprint,status
                     FROM trade_item_operations WHERE trade_id=? ORDER BY created_at,operation_id
                     """)) {
            statement.setObject(1, tradeId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new TradeEscrowRecord(
                            result.getObject("operation_id", UUID.class),
                            result.getObject("trade_id", UUID.class),
                            result.getObject("owner_uuid", UUID.class),
                            result.getObject("recipient_uuid", UUID.class),
                            result.getBytes("item_payload"),
                            result.getString("item_fingerprint").trim(),
                            result.getString("status")));
                }
            }
            return java.util.List.copyOf(items);
        } catch (SQLException | IllegalArgumentException exception) {
            plugin.getLogger().severe("No se pudieron consultar los objetos del intercambio.");
            return java.util.List.of();
        }
    }

    @Override
    public boolean createTradeSession(UUID tradeId, UUID playerA, UUID playerB) {
        if (!isConnected() || tradeId == null || playerA == null || playerB == null || playerA.equals(playerB)) return false;
        String insertTrade = """
                INSERT INTO trade_sessions(trade_id,player_a,player_b,status,revision)
                VALUES (?,?,?,'OPEN',0)
                """;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(insertTrade)) {
                    statement.setObject(1, tradeId);
                    statement.setObject(2, playerA);
                    statement.setObject(3, playerB);
                    statement.executeUpdate();
                }
                try (PreparedStatement lock = connection.prepareStatement(
                        "INSERT INTO trade_player_locks(player_uuid,trade_id) VALUES (?,?)")) {
                    lock.setObject(1, playerA);
                    lock.setObject(2, tradeId);
                    lock.addBatch();
                    lock.setObject(1, playerB);
                    lock.setObject(2, tradeId);
                    lock.addBatch();
                    lock.executeBatch();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                return false;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo crear la sesión de intercambio.");
            return false;
        }
    }

    @Override
    public boolean prepareTradeEscrowItem(TradeEscrowItem item) {
        if (!isConnected() || item == null || !item.verifyPayload()) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID playerA;
                UUID playerB;
                long revision;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT player_a,player_b,revision FROM trade_sessions WHERE trade_id=? AND status='OPEN' FOR UPDATE")) {
                    query.setObject(1, item.tradeId());
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) { connection.rollback(); return false; }
                        playerA = result.getObject(1, UUID.class);
                        playerB = result.getObject(2, UUID.class);
                        revision = result.getLong(3);
                    }
                }
                boolean validPair = (item.ownerUuid().equals(playerA) && item.recipientUuid().equals(playerB))
                        || (item.ownerUuid().equals(playerB) && item.recipientUuid().equals(playerA));
                if (!validPair) { connection.rollback(); return false; }
                try (PreparedStatement pending = connection.prepareStatement("""
                        SELECT 1 FROM trade_item_operations
                        WHERE trade_id=? AND status IN ('PREPARED','RETURN_PENDING','RECOVERY_REQUIRED')
                        LIMIT 1
                        """)) {
                    pending.setObject(1, item.tradeId());
                    try (ResultSet result = pending.executeQuery()) {
                        if (result.next()) { connection.rollback(); return false; }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO trade_item_operations(operation_id,trade_id,owner_uuid,recipient_uuid,
                            item_payload,item_fingerprint,status)
                        VALUES (?,?,?,?,?,?,'PREPARED')
                        """)) {
                    insert.setObject(1, item.escrowItemId());
                    insert.setObject(2, item.tradeId());
                    insert.setObject(3, item.ownerUuid());
                    insert.setObject(4, item.recipientUuid());
                    insert.setBytes(5, item.payload());
                    insert.setString(6, item.fingerprint());
                    insert.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE trade_sessions SET revision=?,confirmed_revision_a=NULL,
                            confirmed_revision_b=NULL,updated_at=now()
                        WHERE trade_id=? AND revision=? AND status='OPEN'
                        """)) {
                    update.setLong(1, Math.addExact(revision, 1));
                    update.setObject(2, item.tradeId());
                    update.setLong(3, revision);
                    if (update.executeUpdate() != 1) throw new SQLException("Trade revision changed unexpectedly.");
                }
                connection.commit();
                return true;
            } catch (SQLException | ArithmeticException exception) {
                connection.rollback();
                return false;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo preparar el objeto para escrow.");
            return false;
        }
    }


    @Override
    public boolean requestTradeItemReturn(UUID operationId, UUID ownerUuid, long expectedRevision) {
        if (!isConnected() || operationId == null || ownerUuid == null || expectedRevision < 0) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID tradeId;
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT trade_id FROM trade_item_operations WHERE operation_id=? AND owner_uuid=? AND status='ESCROWED'")) {
                    find.setObject(1, operationId);
                    find.setObject(2, ownerUuid);
                    try (ResultSet result = find.executeQuery()) {
                        if (!result.next()) { connection.rollback(); return false; }
                        tradeId = result.getObject(1, UUID.class);
                    }
                }
                try (PreparedStatement updateSession = connection.prepareStatement("""
                        UPDATE trade_sessions SET revision=revision+1,confirmed_revision_a=NULL,
                            confirmed_revision_b=NULL,updated_at=now()
                        WHERE trade_id=? AND status='OPEN' AND revision=?
                          AND (player_a=? OR player_b=?)
                        """)) {
                    updateSession.setObject(1, tradeId);
                    updateSession.setLong(2, expectedRevision);
                    updateSession.setObject(3, ownerUuid);
                    updateSession.setObject(4, ownerUuid);
                    if (updateSession.executeUpdate() != 1) { connection.rollback(); return false; }
                }
                try (PreparedStatement updateItem = connection.prepareStatement("""
                        UPDATE trade_item_operations SET status='RETURN_PENDING',updated_at=now()
                        WHERE operation_id=? AND owner_uuid=? AND status='ESCROWED'
                        """)) {
                    updateItem.setObject(1, operationId);
                    updateItem.setObject(2, ownerUuid);
                    if (updateItem.executeUpdate() != 1) { connection.rollback(); return false; }
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                return false;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo preparar la devolución del objeto.");
            return false;
        }
    }

    @Override
    public boolean markTradeItemReturned(UUID operationId, UUID ownerUuid) {
        if (!isConnected() || operationId == null || ownerUuid == null) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE trade_item_operations SET status='RETURNED',updated_at=now()
                     WHERE operation_id=? AND owner_uuid=? AND status IN ('RETURN_PENDING','RETURNED')
                     """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, ownerUuid);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo confirmar la devolución del objeto.");
            return false;
        }
    }

    @Override
    public boolean markTradeEscrowed(UUID operationId) {
        if (!isConnected() || operationId == null) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE trade_item_operations SET status='ESCROWED',updated_at=now()
                     WHERE operation_id=? AND status IN ('PREPARED','ESCROWED')
                     """)) {
            statement.setObject(1, operationId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo confirmar el escrow del objeto.");
            return false;
        }
    }

    @Override
    public boolean advanceTradeRevision(UUID tradeId, UUID actor, long expectedRevision) {
        if (!isConnected() || tradeId == null || actor == null || expectedRevision < 0) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE trade_sessions SET revision=revision+1,confirmed_revision_a=NULL,
                         confirmed_revision_b=NULL,updated_at=now()
                     WHERE trade_id=? AND status='OPEN' AND revision=?
                       AND (player_a=? OR player_b=?)
                     """)) {
            statement.setObject(1, tradeId);
            statement.setLong(2, expectedRevision);
            statement.setObject(3, actor);
            statement.setObject(4, actor);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo actualizar la revisión del intercambio.");
            return false;
        }
    }

    @Override
    public boolean confirmTradeRevision(UUID tradeId, UUID actor, long expectedRevision) {
        if (!isConnected() || tradeId == null || actor == null || expectedRevision < 0) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE trade_sessions SET
                         confirmed_revision_a=CASE WHEN player_a=? THEN revision ELSE confirmed_revision_a END,
                         confirmed_revision_b=CASE WHEN player_b=? THEN revision ELSE confirmed_revision_b END,
                         updated_at=now()
                     WHERE trade_id=? AND status='OPEN' AND revision=? AND (player_a=? OR player_b=?)
                     """)) {
            statement.setObject(1, actor);
            statement.setObject(2, actor);
            statement.setObject(3, tradeId);
            statement.setLong(4, expectedRevision);
            statement.setObject(5, actor);
            statement.setObject(6, actor);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo confirmar la revisión del intercambio.");
            return false;
        }
    }

    @Override
    public boolean lockTradeForSettlement(UUID tradeId, long expectedRevision) {
        if (!isConnected() || tradeId == null || expectedRevision < 0) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE trade_sessions SET status='LOCKED',updated_at=now()
                        WHERE trade_id=? AND status='OPEN' AND revision=?
                          AND confirmed_revision_a=revision AND confirmed_revision_b=revision
                          AND EXISTS (SELECT 1 FROM trade_item_operations o
                                      WHERE o.trade_id=trade_sessions.trade_id AND o.status='ESCROWED')
                          AND NOT EXISTS (SELECT 1 FROM trade_item_operations o
                                          WHERE o.trade_id=trade_sessions.trade_id AND o.status NOT IN ('ESCROWED','RETURNED'))
                        """)) {
                    update.setObject(1, tradeId);
                    update.setLong(2, expectedRevision);
                    changed = update.executeUpdate();
                }
                if (changed != 1) { connection.rollback(); return false; }
                try (PreparedStatement updateItems = connection.prepareStatement("""
                        UPDATE trade_item_operations SET status='DELIVERY_PENDING',updated_at=now()
                        WHERE trade_id=? AND status='ESCROWED'
                        """)) {
                    updateItems.setObject(1, tradeId);
                    updateItems.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                return false;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo bloquear el intercambio para entrega.");
            return false;
        }
    }

    @Override
    public boolean markTradeItemDelivering(UUID operationId, UUID recipientUuid) {
        if (!isConnected() || operationId == null || recipientUuid == null) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE trade_item_operations SET status='DELIVERING',updated_at=now()
                     WHERE operation_id=? AND recipient_uuid=? AND status='DELIVERY_PENDING'
                       AND EXISTS (SELECT 1 FROM trade_sessions t
                                   WHERE t.trade_id=trade_item_operations.trade_id AND t.status='LOCKED')
                     """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, recipientUuid);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo iniciar la entrega del objeto de intercambio.");
            return false;
        }
    }

    @Override
    public boolean markTradeItemDelivered(UUID operationId, UUID recipientUuid) {
        if (!isConnected() || operationId == null || recipientUuid == null) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE trade_item_operations SET status='DELIVERED',delivered_at=now(),updated_at=now()
                     WHERE operation_id=? AND recipient_uuid=? AND status IN ('DELIVERING','DELIVERED')
                     """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, recipientUuid);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo confirmar la entrega del objeto de intercambio.");
            return false;
        }
    }

    @Override
    public boolean markTradeRecoveryRequired(UUID tradeId) {
        if (!isConnected() || tradeId == null) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE trade_sessions SET status='RECOVERY_REQUIRED',updated_at=now()
                        WHERE trade_id=? AND status IN ('OPEN','LOCKED')
                        """)) {
                    update.setObject(1, tradeId);
                    if (update.executeUpdate() != 1) { connection.rollback(); return false; }
                }
                try (PreparedStatement updateItems = connection.prepareStatement("""
                        UPDATE trade_item_operations SET status='RECOVERY_REQUIRED',updated_at=now()
                        WHERE trade_id=? AND status IN ('PREPARED','ESCROWED','RETURN_PENDING','DELIVERY_PENDING','DELIVERING')
                        """)) {
                    updateItems.setObject(1, tradeId);
                    updateItems.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                return false;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo aislar el intercambio para recuperación.");
            return false;
        }
    }

    @Override
    public boolean completeTrade(UUID tradeId) {
        if (!isConnected() || tradeId == null) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE trade_sessions SET status='COMMITTED',updated_at=now()
                        WHERE trade_id=? AND status IN ('LOCKED','RECOVERY_REQUIRED','COMMITTED')
                          AND EXISTS (SELECT 1 FROM trade_item_operations o WHERE o.trade_id=trade_sessions.trade_id)
                          AND NOT EXISTS (SELECT 1 FROM trade_item_operations o
                                          WHERE o.trade_id=trade_sessions.trade_id AND o.status NOT IN ('DELIVERED','RETURNED'))
                        """)) {
                    statement.setObject(1, tradeId);
                    if (statement.executeUpdate() != 1) { connection.rollback(); return false; }
                }
                try (PreparedStatement release = connection.prepareStatement(
                        "DELETE FROM trade_player_locks WHERE trade_id=?")) {
                    release.setObject(1, tradeId);
                    release.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                return false;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo finalizar el intercambio.");
            return false;
        }
    }

    @Override
    public boolean cancelEmptyTrade(UUID tradeId, UUID actor) {
        if (!isConnected() || tradeId == null || actor == null) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE trade_sessions SET status='CANCELLED',updated_at=now()
                        WHERE trade_id=? AND status='OPEN' AND (player_a=? OR player_b=?)
                          AND NOT EXISTS (SELECT 1 FROM trade_item_operations o
                                          WHERE o.trade_id=trade_sessions.trade_id AND o.status<>'RETURNED')
                        """)) {
                    update.setObject(1, tradeId);
                    update.setObject(2, actor);
                    update.setObject(3, actor);
                    if (update.executeUpdate() != 1) { connection.rollback(); return false; }
                }
                try (PreparedStatement release = connection.prepareStatement(
                        "DELETE FROM trade_player_locks WHERE trade_id=?")) {
                    release.setObject(1, tradeId);
                    release.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                return false;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo cancelar el intercambio vacío.");
            return false;
        }
    }


    @Override
    public java.util.List<UUID> expireStaleTrades() {
        if (!isConnected()) return java.util.List.of();
        java.util.List<UUID> expired = new java.util.ArrayList<>();
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT t.trade_id FROM trade_sessions t
                        WHERE t.status='OPEN' AND t.updated_at < now() - INTERVAL '24 hours'
                          AND NOT EXISTS (
                              SELECT 1 FROM trade_item_operations o
                              WHERE o.trade_id=t.trade_id
                                AND o.status NOT IN ('ESCROWED','RETURNED')
                          )
                        FOR UPDATE
                        """);
                     ResultSet result = query.executeQuery()) {
                    while (result.next()) expired.add(result.getObject(1, UUID.class));
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE trade_sessions SET status='EXPIRED',revision=revision+1,
                            confirmed_revision_a=NULL,confirmed_revision_b=NULL,updated_at=now()
                        WHERE trade_id=? AND status='OPEN'
                        """);
                     PreparedStatement returns = connection.prepareStatement("""
                        UPDATE trade_item_operations SET status='RETURN_PENDING',updated_at=now()
                        WHERE trade_id=? AND status='ESCROWED'
                        """);
                     PreparedStatement release = connection.prepareStatement("""
                        DELETE FROM trade_player_locks
                        WHERE trade_id=? AND NOT EXISTS (
                            SELECT 1 FROM trade_item_operations o
                            WHERE o.trade_id=? AND o.status<>'RETURNED'
                        )
                        """)) {
                    for (UUID tradeId : expired) {
                        update.setObject(1, tradeId);
                        if (update.executeUpdate() != 1) continue;
                        returns.setObject(1, tradeId);
                        returns.executeUpdate();
                        release.setObject(1, tradeId);
                        release.setObject(2, tradeId);
                        release.executeUpdate();
                    }
                }
                connection.commit();
                return java.util.List.copyOf(expired);
            } catch (SQLException exception) {
                connection.rollback();
                return java.util.List.of();
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudieron expirar intercambios inactivos.");
            return java.util.List.of();
        }
    }

    @Override
    public boolean completeExpiredTrade(UUID tradeId) {
        if (!isConnected() || tradeId == null) return false;
        try (Connection connection = pool.getConnection();
             PreparedStatement release = connection.prepareStatement("""
                     DELETE FROM trade_player_locks
                     WHERE trade_id=? AND EXISTS (
                         SELECT 1 FROM trade_sessions WHERE trade_id=? AND status='EXPIRED'
                     ) AND NOT EXISTS (
                         SELECT 1 FROM trade_item_operations o
                         WHERE o.trade_id=? AND o.status<>'RETURNED'
                     )
                     """)) {
            release.setObject(1, tradeId);
            release.setObject(2, tradeId);
            release.setObject(3, tradeId);
            if (release.executeUpdate() > 0) return true;
            try (PreparedStatement verify = connection.prepareStatement("""
                    SELECT 1 FROM trade_sessions t
                    WHERE t.trade_id=? AND t.status='EXPIRED'
                      AND NOT EXISTS (SELECT 1 FROM trade_item_operations o
                                      WHERE o.trade_id=t.trade_id AND o.status<>'RETURNED')
                      AND NOT EXISTS (SELECT 1 FROM trade_player_locks l WHERE l.trade_id=t.trade_id)
                    """)) {
                verify.setObject(1, tradeId);
                try (ResultSet result = verify.executeQuery()) { return result.next(); }
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo finalizar la expiración del intercambio.");
            return false;
        }
    }

    @Override
    public boolean replaceClanDirectory(
            java.util.List<ClanRecord> clans, java.util.List<ClanMemberRecord> members) {
        if (!isConnected() || clans == null || members == null) return false;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement inactiveMembers = connection.prepareStatement(
                            "UPDATE player_clan_memberships SET is_active=FALSE");
                     PreparedStatement inactiveClans = connection.prepareStatement(
                            "UPDATE public_clans SET is_active=FALSE")) {
                    inactiveMembers.executeUpdate();
                    inactiveClans.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO public_clans
                            (clan_uuid,name,tag,description,leader_uuid,created_at,member_count,max_members,
                             points,level,kills,deaths,kdr,is_active)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE)
                        ON CONFLICT(clan_uuid) DO UPDATE SET
                            name=EXCLUDED.name, tag=EXCLUDED.tag, description=EXCLUDED.description,
                            leader_uuid=EXCLUDED.leader_uuid, created_at=EXCLUDED.created_at,
                            member_count=EXCLUDED.member_count, max_members=EXCLUDED.max_members,
                            points=EXCLUDED.points, level=EXCLUDED.level, kills=EXCLUDED.kills,
                            deaths=EXCLUDED.deaths, kdr=EXCLUDED.kdr, is_active=TRUE, synced_at=now()
                        """)) {
                    for (ClanRecord clan : clans) {
                        insert.setObject(1, clan.clanId());
                        insert.setString(2, clan.name());
                        insert.setString(3, clan.tag());
                        insert.setString(4, clan.description());
                        insert.setObject(5, clan.leaderUuid());
                        insert.setObject(6, clan.createdAt().atOffset(java.time.ZoneOffset.UTC));
                        insert.setInt(7, clan.memberCount());
                        insert.setInt(8, clan.maxMembers());
                        insert.setLong(9, clan.points());
                        insert.setInt(10, clan.level());
                        insert.setLong(11, clan.kills());
                        insert.setLong(12, clan.deaths());
                        insert.setDouble(13, clan.kdr());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO player_clan_memberships
                            (player_uuid,clan_uuid,rank_id,joined_at,kills,deaths,kdr,is_active)
                        VALUES (?,?,?,?,?,?,?,TRUE)
                        ON CONFLICT(player_uuid) DO UPDATE SET
                            clan_uuid=EXCLUDED.clan_uuid, rank_id=EXCLUDED.rank_id,
                            joined_at=EXCLUDED.joined_at, kills=EXCLUDED.kills,
                            deaths=EXCLUDED.deaths, kdr=EXCLUDED.kdr, is_active=TRUE,
                            synced_at=now()
                        """)) {
                    for (ClanMemberRecord member : members) {
                        insert.setObject(1, member.playerUuid());
                        insert.setObject(2, member.clanUuid());
                        insert.setString(3, member.rankId());
                        insert.setObject(4, member.joinedAt().atOffset(java.time.ZoneOffset.UTC));
                        insert.setLong(5, member.kills());
                        insert.setLong(6, member.deaths());
                        insert.setDouble(7, member.kdr());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                plugin.getLogger().warning("No se pudo actualizar la proyección pública de zMClans; se conserva el último snapshot válido.");
                return false;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("No se pudo abrir una transacción para sincronizar zMClans.");
            return false;
        }
    }


}
