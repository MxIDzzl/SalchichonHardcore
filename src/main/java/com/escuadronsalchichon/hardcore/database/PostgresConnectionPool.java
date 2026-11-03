package com.escuadronsalchichon.hardcore.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns the PostgreSQL connection pool.
 *
 * The pool is selected only when PostgreSQL is explicitly enabled in the
 * host-side configuration. SQLite remains the default backend.
 */
public final class PostgresConnectionPool implements AutoCloseable {

    private final JavaPlugin plugin;
    private HikariDataSource dataSource;

    public PostgresConnectionPool(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Initializes the pool from the host-side plugin configuration.
     * Credentials are read from environment variables and are never logged.
     */
    public synchronized void start() throws SQLException {
        if (dataSource != null && !dataSource.isClosed()) {
            return;
        }

        FileConfiguration config = plugin.getConfig();
        String prefix = "database.postgresql.";
        String host = config.getString(prefix + "host", "127.0.0.1");
        int port = config.getInt(prefix + "port", 5432);
        String database = config.getString(prefix + "database", "salchichon");
        String usernameEnv = config.getString(prefix + "username-env", "SALCHICHON_DB_USER");
        String passwordEnv = config.getString(prefix + "password-env", "SALCHICHON_DB_PASSWORD");

        if (host == null || host.isBlank()
                || database == null || database.isBlank()
                || usernameEnv == null || usernameEnv.isBlank()
                || passwordEnv == null || passwordEnv.isBlank()
                || port < 1 || port > 65535) {
            throw new SQLException("La configuración de PostgreSQL contiene valores inválidos.");
        }

        String username = System.getenv(usernameEnv);
        String password = System.getenv(passwordEnv);
        if (username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new SQLException(
                    "Faltan las variables de entorno requeridas para las credenciales PostgreSQL."
            );
        }

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setPoolName("SalchichonHardcore-PostgreSQL");
        poolConfig.setDriverClassName("org.postgresql.Driver");
        poolConfig.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        poolConfig.setUsername(username);
        poolConfig.setPassword(password);
        int maximumPoolSize = Math.max(2, config.getInt(prefix + "pool.maximum-size", 8));
        int minimumIdle = Math.min(
                maximumPoolSize,
                Math.max(0, config.getInt(prefix + "pool.minimum-idle", 2))
        );
        poolConfig.setMaximumPoolSize(maximumPoolSize);
        poolConfig.setMinimumIdle(minimumIdle);
        poolConfig.setConnectionTimeout(Math.max(250, config.getLong(prefix + "pool.connection-timeout-ms", 10000)));
        poolConfig.setValidationTimeout(Math.max(250, config.getLong(prefix + "pool.validation-timeout-ms", 5000)));
        poolConfig.setMaxLifetime(Math.max(30000, config.getLong(prefix + "pool.max-lifetime-ms", 1800000)));
        poolConfig.setAutoCommit(true);

        try {
            dataSource = new HikariDataSource(poolConfig);
            try (Connection ignored = dataSource.getConnection()) {
                plugin.getLogger().info("Pool PostgreSQL inicializado correctamente.");
            }
        } catch (RuntimeException | SQLException exception) {
            close();
            throw new SQLException("No se pudo inicializar el pool PostgreSQL.", exception);
        }
    }

    public synchronized Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("El pool PostgreSQL no está iniciado.");
        }
        return dataSource.getConnection();
    }

    public synchronized boolean isRunning() {
        return dataSource != null && !dataSource.isClosed();
    }

    @Override
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            plugin.getLogger().info("Pool PostgreSQL cerrado.");
        }
    }
}
