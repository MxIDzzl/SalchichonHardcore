package com.escuadronsalchichon.hardcore.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.SQLException;

/** Owns the MySQL connection pool. Credentials are read only from environment variables. */
public final class MySqlConnectionPool implements AutoCloseable {
    private final JavaPlugin plugin;
    private HikariDataSource dataSource;

    public MySqlConnectionPool(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void start() throws SQLException {
        if (dataSource != null && !dataSource.isClosed()) return;

        FileConfiguration config = plugin.getConfig();
        String prefix = "database.mysql.";
        String jdbcUrl = config.getString(prefix + "jdbc-url", "");
        String usernameEnv = config.getString(prefix + "username-env", "SALCHICHON_MYSQL_USER");
        String passwordEnv = config.getString(prefix + "password-env", "SALCHICHON_MYSQL_PASSWORD");
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")
                || usernameEnv == null || usernameEnv.isBlank()
                || passwordEnv == null || passwordEnv.isBlank()) {
            throw new SQLException("La configuración de MySQL contiene valores inválidos.");
        }

        String username = System.getenv(usernameEnv);
        String password = System.getenv(passwordEnv);
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new SQLException("Faltan las variables de entorno requeridas para las credenciales MySQL.");
        }

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setPoolName("SalchichonHardcore-MySQL");
        poolConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        poolConfig.setJdbcUrl(jdbcUrl);
        poolConfig.setUsername(username);
        poolConfig.setPassword(password);
        int maximumPoolSize = Math.max(2, config.getInt(prefix + "pool.maximum-size", 8));
        int minimumIdle = Math.min(maximumPoolSize,
                Math.max(0, config.getInt(prefix + "pool.minimum-idle", 2)));
        poolConfig.setMaximumPoolSize(maximumPoolSize);
        poolConfig.setMinimumIdle(minimumIdle);
        poolConfig.setConnectionTimeout(Math.max(250,
                config.getLong(prefix + "pool.connection-timeout-ms", 10000)));
        poolConfig.setValidationTimeout(Math.max(250,
                config.getLong(prefix + "pool.validation-timeout-ms", 5000)));
        poolConfig.setMaxLifetime(Math.max(30000,
                config.getLong(prefix + "pool.max-lifetime-ms", 1800000)));
        poolConfig.setAutoCommit(true);
        poolConfig.setConnectionInitSql("SET time_zone = '+00:00'");

        try {
            dataSource = new HikariDataSource(poolConfig);
            try (Connection ignored = dataSource.getConnection()) {
                plugin.getLogger().info("Pool MySQL inicializado correctamente.");
            }
        } catch (RuntimeException | SQLException exception) {
            close();
            throw new SQLException("No se pudo inicializar el pool MySQL.", exception);
        }
    }

    public synchronized Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("El pool MySQL no está iniciado.");
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
            plugin.getLogger().info("Pool MySQL cerrado.");
        }
    }
}
