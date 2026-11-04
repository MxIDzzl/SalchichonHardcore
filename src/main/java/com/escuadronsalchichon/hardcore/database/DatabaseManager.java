
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

    public synchronized boolean resetPlayerCounter(
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

    public synchronized void resetDeathsBefore(LocalDate today) {
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

    public synchronized boolean connect() {
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
            try (var pragma = connection.createStatement()) {
                pragma.execute("PRAGMA foreign_keys = ON");
                pragma.execute("PRAGMA busy_timeout = 5000");
            }

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

    public synchronized boolean createTables() {
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
                    CREATE TABLE IF NOT EXISTS player_value_adjustments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        previous_value INTEGER NOT NULL CHECK (previous_value >= 0),
                        new_value INTEGER NOT NULL CHECK (new_value >= 0),
                        adjustment_delta INTEGER NOT NULL,
                        actor TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (player_uuid) REFERENCES player_data(uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_player_value_adjustments_player
                    ON player_value_adjustments(player_uuid, created_at, id)
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
                    CREATE TABLE IF NOT EXISTS vault_operations (
                        operation_id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL,
                        operation_type TEXT NOT NULL CHECK (operation_type IN ('DEPOSIT','WITHDRAW')),
                        status TEXT NOT NULL CHECK (status IN ('PREPARED','COMMITTED','DELIVERY_PENDING','DELIVERED','CANCELLED','RECOVERY_REQUIRED')),
                        item_id TEXT,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK (amount > 0),
                        item_payload BLOB NOT NULL,
                        credited_value INTEGER NOT NULL CHECK (credited_value >= 0),
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(player_uuid) REFERENCES player_data(uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_vault_operations_pending
                    ON vault_operations(player_uuid, created_at)
                    WHERE status IN ('PREPARED','DELIVERY_PENDING','RECOVERY_REQUIRED')
                    """);
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_vault_operations_one_active_per_player
                    ON vault_operations(player_uuid)
                    WHERE status IN ('PREPARED','DELIVERY_PENDING','RECOVERY_REQUIRED')
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS vault_claims (
                        claim_id TEXT PRIMARY KEY,
                        operation_id TEXT NOT NULL UNIQUE,
                        player_uuid TEXT NOT NULL,
                        item_payload BLOB NOT NULL,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK (amount > 0),
                        status TEXT NOT NULL CHECK (status IN ('PENDING','DELIVERING','DELIVERED','RECOVERY_REQUIRED')),
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        delivered_at TEXT,
                        FOREIGN KEY(operation_id) REFERENCES vault_operations(operation_id),
                        FOREIGN KEY(player_uuid) REFERENCES player_data(uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_vault_claims_player_pending
                    ON vault_claims(player_uuid, created_at)
                    WHERE status IN ('PENDING','DELIVERING','RECOVERY_REQUIRED')
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS vault_cooldowns (
                        player_uuid TEXT PRIMARY KEY,
                        next_allowed_at TEXT NOT NULL,
                        FOREIGN KEY(player_uuid) REFERENCES player_data(uuid) ON DELETE CASCADE
                    )
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

    public synchronized boolean incrementTotems(UUID uuid) {
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

    public synchronized long getTotems(UUID uuid) {
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

    public synchronized boolean savePlayer(PlayerData playerData) {
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


    public synchronized PlayerData getPlayer(UUID uuid) {

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

    public synchronized PlayerData getPlayerByName(String username) {

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


    public synchronized boolean updateDeathDate(UUID uuid, LocalDate deathDate) {
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


    public synchronized boolean restorePlayerLife(
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
    public synchronized boolean recordOfficialDeath(UUID uuid, String username, LocalDate deathDate) {
        if (!isConnected() || uuid == null || username == null || username.isBlank() || deathDate == null) return false;
        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement player = connection.prepareStatement("""
                    INSERT INTO player_data(uuid, username, last_death_date, totem_uses)
                    VALUES (?, ?, NULL, 0)
                    ON CONFLICT(uuid) DO UPDATE SET username = excluded.username
                    """)) {
                player.setString(1, uuid.toString());
                player.setString(2, username);
                player.executeUpdate();
            }
            LocalDate previousDeathDate;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT last_death_date FROM player_data WHERE uuid = ?")) {
                query.setString(1, uuid.toString());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) throw new SQLException("No se encontró el jugador después del upsert.");
                    String storedDate = result.getString(1);
                    previousDeathDate = storedDate == null ? null : LocalDate.parse(storedDate);
                }
            }
            // Repeated delivery of the same death event must not increment counters twice.
            // Admin life restoration clears last_death_date, allowing a later real death.
            if (deathDate.equals(previousDeathDate)) {
                connection.commit();
                return true;
            }
            try (PreparedStatement updateDeath = connection.prepareStatement(
                    "UPDATE player_data SET last_death_date = ? WHERE uuid = ?")) {
                updateDeath.setString(1, deathDate.toString());
                updateDeath.setString(2, uuid.toString());
                if (updateDeath.executeUpdate() != 1) throw new SQLException("No se pudo actualizar la fecha de muerte.");
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
    public synchronized boolean addPlaytimeTicks(UUID uuid, String username, long ticks) {
        if (!isConnected() || uuid == null || username == null || username.isBlank() || ticks <= 0) return false;
        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement player = connection.prepareStatement("""
                    INSERT INTO player_data(uuid, username, last_death_date, totem_uses)
                    VALUES (?, ?, NULL, 0)
                    ON CONFLICT(uuid) DO UPDATE SET username = excluded.username
                    """)) {
                player.setString(1, uuid.toString());
                player.setString(2, username);
                player.executeUpdate();
            }
            try (PreparedStatement stats = connection.prepareStatement("""
                    INSERT INTO player_stats(player_uuid, playtime_ticks, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        playtime_ticks = player_stats.playtime_ticks + excluded.playtime_ticks,
                        updated_at = CURRENT_TIMESTAMP
                    """)) {
                stats.setString(1, uuid.toString());
                stats.setLong(2, ticks);
                stats.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            plugin.getLogger().severe("No se pudo guardar el tiempo de juego de " + username);
            exception.printStackTrace();
            return false;
        } finally {
            try { connection.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) { }
        }
    }

    @Override
    public synchronized long getOfficialDeaths(UUID uuid) { return getOfficialStat(uuid, "official_deaths"); }

    @Override
    public synchronized long getPlaytimeTicks(UUID uuid) { return getOfficialStat(uuid, "playtime_ticks"); }


    @Override
    public synchronized boolean setOfficialStat(UUID uuid, String statKey, long value, String actor, String reason) {
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
            if ("player_value".equals(column)) {
                try (PreparedStatement adjustment = connection.prepareStatement("""
                        INSERT INTO player_value_adjustments(
                            player_uuid, previous_value, new_value, adjustment_delta, actor, reason
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    adjustment.setString(1, uuid.toString());
                    adjustment.setLong(2, previous);
                    adjustment.setLong(3, value);
                    adjustment.setLong(4, Math.subtractExact(value, previous));
                    adjustment.setString(5, actor);
                    adjustment.setString(6, reason.trim());
                    adjustment.executeUpdate();
                }
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



    @Override
    public synchronized boolean prepareVaultDeposit(UUID uuid, String username, VaultItemRecord item) {
        if (!isConnected() || uuid == null || username == null || username.isBlank()
                || item == null || !uuid.equals(item.playerUuid())) return false;
        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement player = connection.prepareStatement("""
                    INSERT INTO player_data(uuid,username,last_death_date,totem_uses)
                    VALUES (?,?,NULL,0)
                    ON CONFLICT(uuid) DO UPDATE SET username=excluded.username
                    """)) {
                player.setString(1, uuid.toString()); player.setString(2, username); player.executeUpdate();
            }
            try (PreparedStatement operation = connection.prepareStatement("""
                    INSERT INTO vault_operations(
                        operation_id,player_uuid,operation_type,status,item_id,material,amount,
                        item_payload,credited_value,created_at,updated_at
                    ) VALUES (?,?,'DEPOSIT','PREPARED',?,?,?,?,?,?,?)
                    """)) {
                operation.setString(1, item.itemId().toString());
                operation.setString(2, uuid.toString());
                operation.setString(3, item.itemId().toString());
                operation.setString(4, item.material());
                operation.setInt(5, item.amount());
                operation.setBytes(6, item.payload());
                operation.setLong(7, item.creditedValue());
                operation.setString(8, item.createdAt().toString());
                operation.setString(9, item.createdAt().toString());
                operation.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            plugin.getLogger().severe("No se pudo preparar el depósito Player Value.");
            exception.printStackTrace(); return false;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException exception) {
                plugin.getLogger().severe("No se pudo restaurar autocommit tras preparar depósito.");
            }
        }
    }

    @Override
    public synchronized boolean commitVaultDeposit(UUID uuid, UUID operationId) {
        if (!isConnected() || uuid == null || operationId == null) return false;
        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            String material; int amount; byte[] payload; long value; java.time.Instant createdAt;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT item_id,material,amount,item_payload,credited_value,created_at,status
                    FROM vault_operations WHERE operation_id=? AND player_uuid=? AND operation_type='DEPOSIT'
                    """)) {
                query.setString(1, operationId.toString()); query.setString(2, uuid.toString());
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) { connection.rollback(); return false; }
                    if ("COMMITTED".equals(row.getString("status"))) { connection.commit(); return true; }
                    if (!"PREPARED".equals(row.getString("status"))) { connection.rollback(); return false; }
                    material=row.getString("material"); amount=row.getInt("amount"); payload=row.getBytes("item_payload");
                    value=row.getLong("credited_value"); createdAt=java.time.Instant.parse(row.getString("created_at"));
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO player_value_items(item_id,player_uuid,material,amount,item_payload,credited_value,created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, operationId.toString()); insert.setString(2, uuid.toString());
                insert.setString(3, material); insert.setInt(4, amount); insert.setBytes(5, payload);
                insert.setLong(6, value); insert.setString(7, createdAt.toString()); insert.executeUpdate();
            }
            try (PreparedStatement stats = connection.prepareStatement("""
                    INSERT INTO player_stats(player_uuid,player_value,updated_at) VALUES (?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(player_uuid) DO UPDATE SET player_value=player_stats.player_value+excluded.player_value,
                    updated_at=CURRENT_TIMESTAMP
                    WHERE player_stats.player_value <= 9223372036854775807 - excluded.player_value
                    """)) {
                stats.setString(1, uuid.toString()); stats.setLong(2, value);
                if (stats.executeUpdate() != 1) throw new SQLException("El Player Value excedería el límite BIGINT.");
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE vault_operations SET status='COMMITTED',updated_at=? WHERE operation_id=? AND player_uuid=? AND status='PREPARED'
                    """)) {
                update.setString(1, java.time.Instant.now().toString()); update.setString(2, operationId.toString());
                update.setString(3, uuid.toString());
                if (update.executeUpdate()!=1) throw new SQLException("La operación dejó de estar PREPARED.");
            }
            connection.commit(); return true;
        } catch (SQLException | RuntimeException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            plugin.getLogger().severe("No se pudo confirmar el depósito Player Value."); exception.printStackTrace(); return false;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException exception) {
                plugin.getLogger().severe("No se pudo restaurar autocommit tras confirmar depósito.");
            }
        }
    }


    @Override
    public synchronized boolean depositVaultItem(UUID uuid, String username, VaultItemRecord item) {
        if (!isConnected() || uuid == null || username == null || username.isBlank()
                || item == null || !uuid.equals(item.playerUuid()) || item.amount() <= 0
                || item.creditedValue() < 0) return false;
        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement player = connection.prepareStatement("""
                    INSERT INTO player_data(uuid,username,last_death_date,totem_uses)
                    VALUES (?,?,NULL,0)
                    ON CONFLICT(uuid) DO UPDATE SET username=excluded.username
                    """)) {
                player.setString(1, uuid.toString()); player.setString(2, username); player.executeUpdate();
            }
            try (PreparedStatement operation = connection.prepareStatement("""
                    INSERT INTO vault_operations(
                        operation_id, player_uuid, operation_type, status, item_id,
                        material, amount, item_payload, credited_value, created_at, updated_at
                    ) VALUES (?, ?, 'DEPOSIT', 'COMMITTED', ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                operation.setString(1, item.itemId().toString());
                operation.setString(2, uuid.toString());
                operation.setString(3, item.itemId().toString());
                operation.setString(4, item.material());
                operation.setInt(5, item.amount());
                operation.setBytes(6, item.payload());
                operation.setLong(7, item.creditedValue());
                operation.setString(8, item.createdAt().toString());
                operation.setString(9, item.createdAt().toString());
                operation.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO player_value_items(item_id,player_uuid,material,amount,item_payload,credited_value,created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, item.itemId().toString()); insert.setString(2, uuid.toString());
                insert.setString(3, item.material()); insert.setInt(4, item.amount());
                insert.setBytes(5, item.payload()); insert.setLong(6, item.creditedValue());
                insert.setString(7, item.createdAt().toString()); insert.executeUpdate();
            }
            try (PreparedStatement stats = connection.prepareStatement("""
                    INSERT INTO player_stats(player_uuid,player_value,updated_at) VALUES (?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(player_uuid) DO UPDATE SET player_value=player_stats.player_value+excluded.player_value,
                    updated_at=CURRENT_TIMESTAMP
                    """)) {
                stats.setString(1, uuid.toString()); stats.setLong(2, item.creditedValue()); stats.executeUpdate();
            }
            connection.commit(); return true;
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            plugin.getLogger().severe("No se pudo depositar el objeto en Player Value."); exception.printStackTrace(); return false;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException exception) {
                plugin.getLogger().severe("No se pudo restaurar autocommit tras depósito.");
            }
        }
    }

    @Override
    public synchronized java.util.List<VaultItemRecord> getVaultItems(UUID uuid, int limit, int offset) {
        if (!isConnected() || uuid == null || limit < 1 || offset < 0) return java.util.List.of();
        java.util.List<VaultItemRecord> items = new java.util.ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT item_id,material,amount,item_payload,credited_value,created_at
                FROM player_value_items WHERE player_uuid=? ORDER BY created_at,item_id LIMIT ? OFFSET ?
                """)) {
            query.setString(1, uuid.toString()); query.setInt(2, limit); query.setInt(3, offset);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) items.add(new VaultItemRecord(
                        UUID.fromString(rows.getString("item_id")), uuid, rows.getString("material"),
                        rows.getInt("amount"), rows.getBytes("item_payload"), rows.getLong("credited_value"),
                        java.time.Instant.parse(rows.getString("created_at"))));
            }
            return java.util.List.copyOf(items);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().severe("No se pudo consultar la bóveda Player Value."); exception.printStackTrace();
            return java.util.List.of();
        }
    }

    @Override
    public synchronized long getPlayerValue(UUID uuid) {
        if (!isConnected() || uuid == null) return 0L;
        try (PreparedStatement query = connection.prepareStatement("SELECT player_value FROM player_stats WHERE player_uuid=?")) {
            query.setString(1, uuid.toString());
            try (ResultSet result = query.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        } catch (SQLException exception) { plugin.getLogger().severe("No se pudo consultar Player Value."); return 0L; }
    }

    @Override
    public synchronized boolean setVaultCooldown(UUID uuid, java.time.Instant nextAllowedAt) {
        if (!isConnected() || uuid == null || nextAllowedAt == null) return false;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO vault_cooldowns(player_uuid,next_allowed_at) VALUES (?,?)
                ON CONFLICT(player_uuid) DO UPDATE SET next_allowed_at=excluded.next_allowed_at
                """)) {
            statement.setString(1, uuid.toString()); statement.setString(2, nextAllowedAt.toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) { plugin.getLogger().severe("No se pudo guardar el cooldown de la bóveda."); return false; }
    }

    @Override
    public synchronized java.time.Instant getVaultCooldown(UUID uuid) {
        if (!isConnected() || uuid == null) return java.time.Instant.EPOCH;
        try (PreparedStatement statement = connection.prepareStatement("SELECT next_allowed_at FROM vault_cooldowns WHERE player_uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? java.time.Instant.parse(result.getString(1)) : java.time.Instant.EPOCH;
            }
        } catch (SQLException | RuntimeException exception) { plugin.getLogger().severe("No se pudo leer el cooldown de la bóveda."); return java.time.Instant.EPOCH; }
    }

    @Override
    public synchronized VaultClaimRecord createVaultWithdrawal(
            UUID uuid, UUID itemId, java.time.Instant now, java.time.Instant nextAllowedAt
    ) {
        if (!isConnected() || uuid == null || itemId == null || now == null
                || nextAllowedAt == null || nextAllowedAt.isBefore(now)) return null;

        boolean oldAutoCommit = true;
        try {
            oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try (PreparedStatement cooldown = connection.prepareStatement(
                    "SELECT next_allowed_at FROM vault_cooldowns WHERE player_uuid=?")) {
                cooldown.setString(1, uuid.toString());
                try (ResultSet result = cooldown.executeQuery()) {
                    if (result.next()) {
                        java.time.Instant allowedAt = java.time.Instant.parse(result.getString(1));
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
                    "SELECT material,amount,item_payload,credited_value FROM player_value_items WHERE item_id=? AND player_uuid=?")) {
                query.setString(1, itemId.toString());
                query.setString(2, uuid.toString());
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
                delete.setString(1, itemId.toString());
                delete.setString(2, uuid.toString());
                if (delete.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
            }

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE player_stats SET player_value=player_value-?,updated_at=CURRENT_TIMESTAMP WHERE player_uuid=? AND player_value>=?")) {
                update.setLong(1, value);
                update.setString(2, uuid.toString());
                update.setLong(3, value);
                if (update.executeUpdate() != 1) throw new SQLException("Player Value desincronizado");
            }

            UUID operationId = UUID.randomUUID();
            UUID claimId = UUID.randomUUID();
            try (PreparedStatement operation = connection.prepareStatement(
                    "INSERT INTO vault_operations(operation_id,player_uuid,operation_type,status,item_id,material,amount,item_payload,credited_value) VALUES (?,?,'WITHDRAW','DELIVERY_PENDING',?,?,?,?,?)")) {
                operation.setString(1, operationId.toString());
                operation.setString(2, uuid.toString());
                operation.setString(3, itemId.toString());
                operation.setString(4, material);
                operation.setInt(5, amount);
                operation.setBytes(6, payload);
                operation.setLong(7, value);
                operation.executeUpdate();
            }
            try (PreparedStatement claim = connection.prepareStatement(
                    "INSERT INTO vault_claims(claim_id,operation_id,player_uuid,item_payload,material,amount,status) VALUES (?,?,?,?,?,?,'PENDING')")) {
                claim.setString(1, claimId.toString());
                claim.setString(2, operationId.toString());
                claim.setString(3, uuid.toString());
                claim.setBytes(4, payload);
                claim.setString(5, material);
                claim.setInt(6, amount);
                claim.executeUpdate();
            }
            try (PreparedStatement cooldown = connection.prepareStatement("""
                    INSERT INTO vault_cooldowns(player_uuid,next_allowed_at) VALUES (?,?)
                    ON CONFLICT(player_uuid) DO UPDATE SET next_allowed_at=excluded.next_allowed_at
                    """)) {
                cooldown.setString(1, uuid.toString());
                cooldown.setString(2, nextAllowedAt.toString());
                cooldown.executeUpdate();
            }

            connection.commit();
            return new VaultClaimRecord(claimId, operationId, uuid, material, amount, payload, "PENDING");
        } catch (SQLException | java.time.DateTimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            plugin.getLogger().severe("No se pudo crear el retiro de Player Value.");
            exception.printStackTrace();
            return null;
        } finally {
            try {
                connection.setAutoCommit(oldAutoCommit);
            } catch (SQLException exception) {
                plugin.getLogger().severe("No se pudo restaurar el estado de la conexión SQLite.");
                exception.printStackTrace();
            }
        }
    }

    @Override
    public synchronized boolean markVaultClaimDelivering(UUID claimId, UUID playerUuid) {
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
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, claimId.toString());
            statement.setString(2, playerUuid.toString());
            statement.setString(3, playerUuid.toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            plugin.getLogger().severe("No se pudo iniciar la entrega del retiro.");
            return false;
        }
    }

    @Override
    public synchronized boolean markVaultClaimDelivered(UUID claimId, UUID playerUuid) {
        if (!isConnected() || claimId == null || playerUuid == null) return false;
        boolean oldAuto = true;
        try {
            oldAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            UUID operationId;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE vault_claims SET status='DELIVERED', delivered_at=CURRENT_TIMESTAMP
                    WHERE claim_id=? AND player_uuid=? AND status='DELIVERING'
                    """)) {
                update.setString(1, claimId.toString());
                update.setString(2, playerUuid.toString());
                if (update.executeUpdate() != 1) { connection.rollback(); return false; }
            }
            try (PreparedStatement lookup = connection.prepareStatement("SELECT operation_id FROM vault_claims WHERE claim_id=?")) {
                lookup.setString(1, claimId.toString());
                try (ResultSet result = lookup.executeQuery()) {
                    if (!result.next()) throw new SQLException("No existe la reclamación");
                    operationId = UUID.fromString(result.getString(1));
                }
            }
            try (PreparedStatement operation = connection.prepareStatement("""
                    UPDATE vault_operations SET status='DELIVERED', updated_at=CURRENT_TIMESTAMP
                    WHERE operation_id=? AND status='DELIVERY_PENDING'
                    """)) {
                operation.setString(1, operationId.toString());
                if (operation.executeUpdate() != 1) throw new SQLException("Estado de operación inconsistente");
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            plugin.getLogger().severe("No se pudo cerrar atómicamente el retiro.");
            exception.printStackTrace();
            return false;
        } finally {
            try { connection.setAutoCommit(oldAuto); } catch (SQLException ignored) { }
        }
    }


    @Override
    public synchronized java.util.List<VaultClaimRecord> getPendingVaultClaims(UUID playerUuid){
        if(!isConnected()||playerUuid==null)return java.util.List.of();
        java.util.List<VaultClaimRecord> out=new java.util.ArrayList<>();
        try(PreparedStatement s=connection.prepareStatement("SELECT claim_id,operation_id,item_payload,material,amount,status FROM vault_claims WHERE player_uuid=? AND status='PENDING' ORDER BY created_at")){
            s.setString(1,playerUuid.toString());try(ResultSet r=s.executeQuery()){while(r.next())out.add(new VaultClaimRecord(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),playerUuid,r.getString(4),r.getInt(5),r.getBytes(3),r.getString(6)));}return java.util.List.copyOf(out);
        }catch(SQLException e){plugin.getLogger().severe("No se pudieron consultar retiros pendientes.");return java.util.List.of();}
    }

    @Override
    public synchronized int flagUncertainVaultOperations() {
        if (!isConnected()) return 0;
        boolean oldAuto = true;
        try {
            oldAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            int changed;
            try (PreparedStatement claims = connection.prepareStatement(
                    "UPDATE vault_claims SET status='RECOVERY_REQUIRED' WHERE status='DELIVERING'")) {
                changed = claims.executeUpdate();
            }
            try (PreparedStatement operations = connection.prepareStatement(
                    "UPDATE vault_operations SET status='RECOVERY_REQUIRED',updated_at=CURRENT_TIMESTAMP WHERE status='DELIVERY_PENDING' AND operation_type='WITHDRAW' AND operation_id IN (SELECT operation_id FROM vault_claims WHERE status='RECOVERY_REQUIRED')")) {
                operations.executeUpdate();
            }
            // A PREPARED deposit may have been interrupted between journal creation
            // and inventory mutation. Without an inventory marker, never guess.
            try (PreparedStatement deposits = connection.prepareStatement(
                    "UPDATE vault_operations SET status='RECOVERY_REQUIRED',updated_at=CURRENT_TIMESTAMP WHERE operation_type='DEPOSIT' AND status='PREPARED'")) {
                changed += deposits.executeUpdate();
            }
            connection.commit();
            return changed;
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            plugin.getLogger().severe("No se pudieron marcar reclamaciones inciertas para recuperación.");
            exception.printStackTrace();
            return -1;
        } finally {
            try { connection.setAutoCommit(oldAuto); } catch (SQLException ignored) { }
        }
    }

    public synchronized void disconnect() {
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
