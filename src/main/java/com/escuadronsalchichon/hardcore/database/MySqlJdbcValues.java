package com.escuadronsalchichon.hardcore.database;

import java.sql.Calendar;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Centralizes MySQL's portable representations for values that were native
 * PostgreSQL UUID/TIMESTAMPTZ values. DATETIME columns are treated as UTC.
 */
public final class MySqlJdbcValues {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private MySqlJdbcValues() {
    }

    public static void bindUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.CHAR);
            return;
        }
        statement.setString(index, value.toString());
    }

    public static UUID readUuid(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("UUID inválido almacenado en la columna " + column, exception);
        }
    }

    public static void bindInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.TIMESTAMP);
            return;
        }
        statement.setTimestamp(index, Timestamp.from(value), utcCalendar());
    }

    public static Instant readInstant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column, utcCalendar());
        return value == null ? null : value.toInstant();
    }

    private static Calendar utcCalendar() {
        return Calendar.getInstance(UTC);
    }
}
