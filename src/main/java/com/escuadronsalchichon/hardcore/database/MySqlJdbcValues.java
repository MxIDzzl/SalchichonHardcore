package com.escuadronsalchichon.hardcore.database;

import java.util.Calendar;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.LocalDate;
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

    public static void bindValue(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.NULL);
        } else if (value instanceof UUID uuid) {
            bindUuid(statement, index, uuid);
        } else if (value instanceof Instant instant) {
            bindInstant(statement, index, instant);
        } else if (value instanceof OffsetDateTime dateTime) {
            bindInstant(statement, index, dateTime.toInstant());
        } else if (value instanceof LocalDate date) {
            statement.setDate(index, java.sql.Date.valueOf(date));
        } else {
            statement.setObject(index, value);
        }
    }

    public static UUID readUuid(ResultSet result, int column) throws SQLException {
        String value = result.getString(column);
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("UUID inválido almacenado en la columna " + column, exception);
        }
    }

    public static OffsetDateTime readOffsetDateTime(ResultSet result, String column) throws SQLException {
        Instant value = readInstant(result, column);
        return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
    }

    public static OffsetDateTime readOffsetDateTime(ResultSet result, int column) throws SQLException {
        Timestamp value = result.getTimestamp(column, utcCalendar());
        return value == null ? null : value.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static Calendar utcCalendar() {
        return Calendar.getInstance(UTC);
    }
}
