
package com.escuadronsalchichon.hardcore.database;

import com.escuadronsalchichon.hardcore.player.PlayerData;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class DatabaseManager implements PlayerDataStore {

    public boolean resetPlayerCounter(
            UUID targetUuid,
            String targetName,
            String adminName,
            String reason
    ) {
        if (!isConnected()
                || targetUuid == null
                || targetName == null || targetName.isBlank()
                || adminName == null || adminName.isBlank()
                || reason == null || reason.isBlank()) {
            return false;
        }

        String updateSql = """
            UPDATE player_data SET last_death_date = NULL WHERE uuid = ?
            """;
        String auditSql = """
            INSERT INTO admin_audit (
                target_uuid, target_name, admin_name, action, reason, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;

        boolean originalAutoCommit = true;
        boolean transactionStarted = false;

        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;

            try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                update.setString(1, targetUuid.toString());
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }

            try (PreparedStatement audit = connection.prepareStatement(auditSql)) {
                audit.setString(1, targetUuid.toString());
                audit.setString(2, targetName);
                audit.setString(3, adminName);
                audit.setString(4, "RESET_COUNTER");
                audit.setString(5, reason.trim());
                audit.setString(6, getAuditTimestamp());
                audit.executeUpdate();
            }

            connection.commit();
            return true;
        } catch (SQLException exception) {
            if (transactionStarted) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
            }
            plugin.getLogger().severe("No se pudo reiniciar el contador de " + targetName);
            exception.printStackTrace();
            return false;
        } finally {
            if (transactionStarted) {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException exception) {
                    plugin.getLogger().severe("No se pudo restaurar el estado de la transacción SQLite.");
                    exception.printStackTrace();
                }
            }
        }
    }

    private String getAuditTimestamp() {
        String configuredZone = plugin.getConfig().getString(
                "settings.timezone",
                "America/Mexico_City"
        );
        try {
            return LocalDateTime.now(ZoneId.of(configuredZone)).toString();
        } catch (java.time.DateTimeException exception) {
            plugin.getLogger().warning(
                    "Zona horaria inválida para auditoría: " + configuredZone
                            + ". Se utilizará America/Mexico_City."
            );
            return LocalDateTime.now(ZoneId.of("America/Mexico_City")).toString();
        }
    }

    public void resetDeathsBefore(LocalDate today) {
        if (today == null) {
            throw new IllegalArgumentException("La fecha actual no puede ser null.");
        }
        if (!isConnected()) {
            throw new IllegalStateException("No hay conexión disponible con SQLite.");
        }

        String sql = """
            UPDATE player_data
            SET last_death_date = NULL
            WHERE last_death_date IS NOT NULL
              AND last_death_date < ?
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, today.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "No se pudieron reiniciar las vidas expiradas.",
                    exception
            );
        }
    }

    private final JavaPlugin plugin;
    private Connection connection;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException exception) {
            return false;
        }
    }

    public boolean connect() {
        try {
            if (isConnected()) {
                return true;
            }

            File dataFolder = plugin.getDataFolder();

            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                plugin.getLogger().severe(
                        "No se pudo crear la carpeta del plugin."
                );
                return false;
            }

            File databaseFile = new File(dataFolder, "hardcore.db");
            String url = "jdbc:sqlite:" + databaseFile.getAbsolutePath();

            connection = DriverManager.getConnection(url);

            plugin.getLogger().info(
                    "Conexion con SQLite establecida."
            );

            return true;

        } catch (SQLException exception) {
            plugin.getLogger().severe(
                    "No se pudo conectar con SQLite."
            );
            exception.printStackTrace();
            return false;
        }
    }

    public boolean createTables() {
        if (!isConnected()) {
            plugin.getLogger().severe(
                    "No se pueden crear tablas: SQLite no esta conectado."
            );
            return false;
        }

        String playerTable = """
                CREATE TABLE IF NOT EXISTS player_data (
                    uuid TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    last_death_date TEXT
                );
                """;

        String auditTable = """
                CREATE TABLE IF NOT EXISTS admin_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    target_uuid TEXT NOT NULL,
                    target_name TEXT NOT NULL,
                    admin_name TEXT NOT NULL,
                    action TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                """;

        try (var statement = connection.createStatement()) {
            statement.executeUpdate(playerTable);
            statement.executeUpdate(auditTable);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        player_uuid TEXT PRIMARY KEY,
                        official_deaths INTEGER NOT NULL DEFAULT 0 CHECK (official_deaths >= 0),
                        playtime_ticks INTEGER NOT NULL DEFAULT 0 CHECK (playtime_ticks >= 0),
                        player_value INTEGER NOT NULL DEFAULT 0 CHECK (player_value >= 0),
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS stat_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        stat_key TEXT NOT NULL,
                        previous_value INTEGER NOT NULL,
                        new_value INTEGER NOT NULL,
                        actor TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_value_items (
                        item_id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK (amount > 0),
                        item_payload BLOB NOT NULL,
                        credited_value INTEGER NOT NULL CHECK (credited_value >= 0),
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (player_uuid) REFERENCES player_data(uuid) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_player_value_items_owner
                    ON player_value_items(player_uuid, created_at, item_id)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trade_sessions (
                        trade_id TEXT PRIMARY KEY,
                        player_a TEXT NOT NULL,
                        player_b TEXT NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('OPEN','LOCKED','COMMITTED','CANCELLED','EXPIRED','RECOVERY_REQUIRED')),
                        revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
                        confirmed_revision_a INTEGER,
                        confirmed_revision_b INTEGER,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CHECK (player_a <> player_b),
                        FOREIGN KEY (player_a) REFERENCES player_data(uuid),
                        FOREIGN KEY (player_b) REFERENCES player_data(uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trade_escrow_items (
                        escrow_item_id TEXT PRIMARY KEY,
                        trade_id TEXT NOT NULL,
                        owner_uuid TEXT NOT NULL,
                        recipient_uuid TEXT NOT NULL,
                        item_payload BLOB NOT NULL,
                        item_fingerprint TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        delivered_at TEXT,
                        CHECK (owner_uuid <> recipient_uuid),
                        FOREIGN KEY (trade_id) REFERENCES trade_sessions(trade_id),
                        FOREIGN KEY (owner_uuid) REFERENCES player_data(uuid),
                        FOREIGN KEY (recipient_uuid) REFERENCES player_data(uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_trade_escrow_pending
                    ON trade_escrow_items(recipient_uuid, trade_id) WHERE delivered_at IS NULL
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS operation_receipts (
                        operation_id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL,
                        operation_type TEXT NOT NULL,
                        result_reference TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (player_uuid) REFERENCES player_data(uuid)
                    )
                    """);

            // Migración segura: conserva todos los datos existentes.
            boolean hasTotemUses = false;
            try (var columns = statement.executeQuery("PRAGMA table_info(player_data)")) {
                while (columns.next()) {
                    if ("totem_uses".equalsIgnoreCase(columns.getString("name"))) {
                        hasTotemUses = true;
                        break;
                    }
                }
            }
            if (!hasTotemUses) {
                statement.executeUpdate(
                        "ALTER TABLE player_data ADD COLUMN totem_uses INTEGER NOT NULL DEFAULT 0"
                );
            }

            plugin.getLogger().info(
                    "Tablas SQLite creadas correctamente."
            );

            return true;

        } catch (SQLException exception) {
            plugin.getLogger().severe(
                    "No se pudieron crear las tablas SQLite."
            );
            exception.printStackTrace();
            return false;
        }
    }

    public boolean incrementTotems(UUID uuid) {
        if (!isConnected() || uuid == null) return false;
        String sql = """
                INSERT INTO player_data (uuid, username, last_death_date, totem_uses)
                VALUES (?, ?, NULL, 1)
                ON CONFLICT(uuid) DO UPDATE SET totem_uses = player_data.totem_uses + 1;
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, "Unknown");
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo registrar el uso de tótem.");
            exception.printStackTrace();
            return false;
        }
    }

    public long getTotems(UUID uuid) {
        if (!isConnected() || uuid == null) return 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT totem_uses FROM player_data WHERE uuid = ?"
        )) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong("totem_uses") : 0L;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo consultar el uso de tótems.");
            exception.printStackTrace();
            return 0;
        }
    }

    public boolean savePlayer(PlayerData playerData) {
        if (!isConnected() || playerData == null) {
            plugin.getLogger().severe(
                    "No se pudieron guardar los datos: conexion invalida o jugador nulo."
            );
            return false;
        }

        String sql = """
                INSERT INTO player_data (uuid, username, last_death_date)
                VALUES (?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    last_death_date = excluded.last_death_date;
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, playerData.getUuid().toString());
            statement.setString(2, playerData.getUsername());

            if (playerData.getLastDeathDate() != null) {
                statement.setString(
                        3,
                        playerData.getLastDeathDate().toString()
                );
            } else {
                statement.setNull(3, Types.VARCHAR);
            }

            statement.executeUpdate();
            return true;

        } catch (SQLException exception) {
            plugin.getLogger().severe(
                    "No se pudieron guardar los datos del jugador."
            );
            exception.printStackTrace();
            return false;
        }
    }


    public PlayerData getPlayer(UUID uuid) {

        if (uuid == null) {
            throw new IllegalArgumentException(
                    "El UUID del jugador no puede ser null."
            );
        }

        if (!isConnected()) {
            throw new IllegalStateException(
                    "No hay conexión disponible con SQLite."
            );
        }

        String sql = """
            SELECT username, last_death_date
            FROM player_data
            WHERE uuid = ?;
            """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, uuid.toString());

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return null;
                }

                String username = result.getString("username");
                String deathDateString =
                        result.getString("last_death_date");

                LocalDate lastDeathDate = null;

                if (deathDateString != null) {
                    try {
                        lastDeathDate = LocalDate.parse(deathDateString);
                    } catch (DateTimeParseException exception) {
                        throw new IllegalStateException(
                                "Fecha de muerte corrupta para UUID: " + uuid,
                                exception
                        );
                    }
                }

                return new PlayerData(
                        uuid,
                        username,
                        lastDeathDate
                );
            }

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Error al recuperar los datos del jugador: " + uuid,
                    exception
            );
        }
    }

    public PlayerData getPlayerByName(String username) {

        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del jugador no puede estar vacio."
            );
        }

        if (!isConnected()) {
            throw new IllegalStateException(
                    "No hay conexion disponible con SQLite."
            );
        }

        String sql = """
            SELECT uuid, username, last_death_date
            FROM player_data
            WHERE username = ? COLLATE NOCASE
            LIMIT 1;
            """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, username.trim());

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return null;
                }

                UUID uuid = UUID.fromString(
                        result.getString("uuid")
                );

                String savedUsername = result.getString("username");
                String deathDateString =
                        result.getString("last_death_date");

                LocalDate lastDeathDate = null;

                if (deathDateString != null) {
                    try {
                        lastDeathDate = LocalDate.parse(deathDateString);
                    } catch (DateTimeParseException exception) {
                        throw new IllegalStateException(
                                "Fecha de muerte corrupta para UUID: " + uuid,
                                exception
                        );
                    }
                }

                return new PlayerData(
                        uuid,
                        savedUsername,
                        lastDeathDate
                );
            }

        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Error al buscar al jugador: " + username,
                    exception
            );
        }
    }


    public boolean updateDeathDate(UUID uuid, LocalDate deathDate) {
        if (!isConnected() || uuid == null || deathDate == null) {
            plugin.getLogger().severe(
                    "No se pudo actualizar la fecha: datos invalidos o SQLite desconectado."
            );
            return false;
        }

        String sql = """
                UPDATE player_data
                SET last_death_date = ?
                WHERE uuid = ?;
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, deathDate.toString());
            statement.setString(2, uuid.toString());

            int rowsAffected = statement.executeUpdate();

            return rowsAffected > 0;

        } catch (SQLException exception) {
            plugin.getLogger().severe(
                    "No se pudo actualizar la fecha de muerte."
            );
            exception.printStackTrace();
            return false;
        }
    }


    public boolean restorePlayerLife(
            UUID targetUuid,
            String targetName,
            String adminName,
            String reason
    ) {

        if (!isConnected()
                || targetUuid == null
                || targetName == null || targetName.isBlank()
                || adminName == null || adminName.isBlank()
                || reason == null || reason.isBlank()) {

            plugin.getLogger().severe(
                    "No se pudo restaurar la vida: datos invalidos."
            );

            return false;
        }

        String updateSql = """
            UPDATE player_data
            SET last_death_date = NULL
            WHERE uuid = ?;
            """;

        String auditSql = """
            INSERT INTO admin_audit (
                target_uuid,
                target_name,
                admin_name,
                action,
                reason,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?);
            """;

        boolean originalAutoCommit = true;
        boolean transactionStarted = false;

        try {
            originalAutoCommit = connection.getAutoCommit();

            connection.setAutoCommit(false);
            transactionStarted = true;

            // Restaurar la vida del jugador.
            try (PreparedStatement statement =
                         connection.prepareStatement(updateSql)) {

                statement.setString(1, targetUuid.toString());

                int rowsAffected = statement.executeUpdate();

                if (rowsAffected != 1) {
                    connection.rollback();
                    return false;
                }
            }

            // Registrar la acción administrativa.
            try (PreparedStatement statement =
                         connection.prepareStatement(auditSql)) {

                statement.setString(1, targetUuid.toString());
                statement.setString(2, targetName);
                statement.setString(3, adminName);
                statement.setString(4, "RESTORE_LIFE");
                statement.setString(5, reason.trim());

                statement.setString(
                        6,
                        getAuditTimestamp()
                );

                statement.executeUpdate();
            }

            // Confirmar ambas operaciones.
            connection.commit();

            plugin.getLogger().info(
                    "Vida restaurada a " + targetName
                            + " por " + adminName
            );

            return true;

        } catch (SQLException exception) {

            if (transactionStarted) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
            }

            plugin.getLogger().severe(
                    "Error al restaurar la vida de " + targetName
            );

            exception.printStackTrace();

            return false;

        } finally {

            if (transactionStarted) {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException exception) {
                    plugin.getLogger().severe(
                            "No se pudo restaurar el estado de la transaccion SQLite."
                    );
                    exception.printStackTrace();
                }
            }
        }
    }


    @Override
    public boolean recordOfficialDeath(UUID uuid, String username, LocalDate deathDate) {
        if (!isConnected() || uuid == null || username == null || username.isBlank() || deathDate == null) return false;
        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement player = connection.prepareStatement("""
                    INSERT INTO player_data(uuid, username, last_death_date, totem_uses)
                    VALUES (?, ?, ?, 0)
                    ON CONFLICT(uuid) DO UPDATE SET username = excluded.username,
                        last_death_date = excluded.last_death_date
                    """)) {
                player.setString(1, uuid.toString());
                player.setString(2, username);
                player.setString(3, deathDate.toString());
                player.executeUpdate();
            }
            long previous = 0;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT official_deaths FROM player_stats WHERE player_uuid = ?")) {
                query.setString(1, uuid.toString());
                try (ResultSet result = query.executeQuery()) { if (result.next()) previous = result.getLong(1); }
            }
            try (PreparedStatement stats = connection.prepareStatement("""
                    INSERT INTO player_stats(player_uuid, official_deaths, updated_at)
                    VALUES (?, 1, CURRENT_TIMESTAMP)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        official_deaths = player_stats.official_deaths + 1,
                        updated_at = CURRENT_TIMESTAMP
                    """)) {
                stats.setString(1, uuid.toString());
                stats.executeUpdate();
            }
            try (PreparedStatement event = connection.prepareStatement("""
                    INSERT INTO stat_events(player_uuid, stat_key, previous_value, new_value, actor, reason)
                    VALUES (?, 'official_deaths', ?, ?, 'SERVER', 'PLAYER_DEATH')
                    """)) {
                event.setString(1, uuid.toString());
                event.setLong(2, previous);
                event.setLong(3, previous + 1);
                event.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            plugin.getLogger().severe("No se pudo registrar la muerte oficial de " + username);
            exception.printStackTrace();
            return false;
        } finally {
            try { connection.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) { }
        }
    }

    @Override
    public boolean addPlaytimeTicks(UUID uuid, String username, long ticks) {
        if (!isConnected() || uuid == null || username == null || username.isBlank() || ticks <= 0) return false;
        try {
            try (PreparedStatement player = connection.prepareStatement("""
                    INSERT INTO player_data(uuid, username, last_death_date, totem_uses)
                    VALUES (?, ?, NULL, 0)
                    ON CONFLICT(uuid) DO UPDATE SET username = excluded.username
                    """)) {
                player.setString(1, uuid.toString()); player.setString(2, username); player.executeUpdate();
            }
            try (PreparedStatement stats = connection.prepareStatement("""
                    INSERT INTO player_stats(player_uuid, playtime_ticks, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        playtime_ticks = player_stats.playtime_ticks + excluded.playtime_ticks,
                        updated_at = CURRENT_TIMESTAMP
                    """)) {
                stats.setString(1, uuid.toString()); stats.setLong(2, ticks); return stats.executeUpdate() == 1;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo guardar el tiempo de juego de " + username);
            exception.printStackTrace(); return false;
        }
    }

    @Override
    public long getOfficialDeaths(UUID uuid) { return getOfficialStat(uuid, "official_deaths"); }

    @Override
    public long getPlaytimeTicks(UUID uuid) { return getOfficialStat(uuid, "playtime_ticks"); }


    @Override
    public boolean setOfficialStat(UUID uuid, String statKey, long value, String actor, String reason) {
        if (!isConnected() || uuid == null || value < 0 || actor == null || actor.isBlank() || reason == null || reason.isBlank()) return false;
        String column = switch (statKey == null ? "" : statKey.toLowerCase(java.util.Locale.ROOT)) {
            case "deaths" -> "official_deaths";
            case "playtime" -> "playtime_ticks";
            case "value" -> "player_value";
            default -> null;
        };
        if (column == null) return false;
        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            long previous = 0;
            try (PreparedStatement query = connection.prepareStatement("SELECT " + column + " FROM player_stats WHERE player_uuid = ?")) {
                query.setString(1, uuid.toString());
                try (ResultSet result = query.executeQuery()) { if (result.next()) previous = result.getLong(1); }
            }
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO player_stats(player_uuid, official_deaths, playtime_ticks, player_value, updated_at)
                    VALUES (?, 0, 0, 0, CURRENT_TIMESTAMP)
                    ON CONFLICT(player_uuid) DO NOTHING
                    """)) { upsert.setString(1, uuid.toString()); upsert.executeUpdate(); }
            try (PreparedStatement update = connection.prepareStatement("UPDATE player_stats SET " + column + " = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
                update.setLong(1, value); update.setString(2, uuid.toString());
                if (update.executeUpdate() != 1) throw new SQLException("No se actualizó la estadística.");
            }
            try (PreparedStatement event = connection.prepareStatement("""
                    INSERT INTO stat_events(player_uuid, stat_key, previous_value, new_value, actor, reason)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                event.setString(1, uuid.toString()); event.setString(2, column); event.setLong(3, previous);
                event.setLong(4, value); event.setString(5, actor); event.setString(6, reason.trim()); event.executeUpdate();
            }
            connection.commit(); return true;
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            plugin.getLogger().severe("No se pudo corregir la estadística oficial."); exception.printStackTrace(); return false;
        } finally { try { connection.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) { } }
    }

    private long getOfficialStat(UUID uuid, String column) {
        if (!isConnected() || uuid == null) return 0L;
        if (!column.equals("official_deaths") && !column.equals("playtime_ticks")) throw new IllegalArgumentException("Estadística inválida");
        try (PreparedStatement query = connection.prepareStatement("SELECT " + column + " FROM player_stats WHERE player_uuid = ?")) {
            query.setString(1, uuid.toString());
            try (ResultSet result = query.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo consultar la estadística oficial " + column);
            exception.printStackTrace(); return 0L;
        }
    }


    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();

                plugin.getLogger().info(
                        "Conexion con SQLite cerrada."
                );
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe(
                    "Error al cerrar la conexion SQLite."
            );
            exception.printStackTrace();
        } finally {
            connection = null;
        }
    }
}
