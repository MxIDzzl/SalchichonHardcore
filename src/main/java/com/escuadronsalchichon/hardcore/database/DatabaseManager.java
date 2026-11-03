
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

public class DatabaseManager {

    public boolean resetPlayerCounter(
            UUID targetUuid,
            String targetName,
            String adminName,
            String reason
    ) {
        if (targetUuid == null
                || targetName == null
                || adminName == null
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException("Los datos del reset no son válidos.");
        }

        if (connection == null) {
            throw new IllegalStateException("La base de datos no está conectada.");
        }

        String updateSql = """
            UPDATE player_data
            SET last_death_date = NULL
            WHERE uuid = ?
            """;

        String auditSql = """
            INSERT INTO admin_audit (
                target_uuid, target_name, admin_name,
                action, reason, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;

        boolean originalAutoCommit;

        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try (PreparedStatement update =
                         connection.prepareStatement(updateSql);
                 PreparedStatement audit =
                         connection.prepareStatement(auditSql)) {

                update.setString(1, targetUuid.toString());

                if (update.executeUpdate() == 0) {
                    connection.rollback();
                    return false;
                }

                audit.setString(1, targetUuid.toString());
                audit.setString(2, targetName);
                audit.setString(3, adminName);
                audit.setString(4, "RESET_COUNTER");
                audit.setString(5, reason);
                audit.setString(
                        6,
                        LocalDateTime.now(
                                ZoneId.of("America/Mexico_City")
                        ).toString()
                );

                audit.executeUpdate();
                connection.commit();
                return true;

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;

            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }

        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "No se pudo reiniciar el contador del jugador.",
                    exception
            );
        }
    }

    public void resetDeathsBefore(LocalDate today) {
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
                        LocalDateTime.now(
                                ZoneId.of("America/Mexico_City")
                        ).toString()
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
