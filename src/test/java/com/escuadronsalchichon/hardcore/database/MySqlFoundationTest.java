package com.escuadronsalchichon.hardcore.database;

import org.junit.jupiter.api.Test;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MySqlFoundationTest {

    @Test
    void schemaUsesInnoDbAndContainsRequiredPersistenceTables() throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/mysql/schema-v1.sql")) {
            assertNotNull(stream, "MySQL schema resource must be packaged");
            String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(schema.contains("ENGINE=InnoDB"));
            assertTrue(schema.contains("DEFAULT CHARSET=utf8mb4"));
            assertTrue(schema.contains("GENERATED ALWAYS AS"));
            assertTrue(schema.contains("uq_vault_operations_one_active_per_player"));
            assertTrue(schema.contains("uq_trade_sessions_active_a"));
            for (String table : new String[] {
                    "player_data", "player_stats", "stat_events", "admin_audit",
                    "player_value_items", "vault_operations", "vault_claims",
                    "vault_cooldowns", "player_skins", "public_clans",
                    "player_clan_memberships"
            }) {
                assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table),
                        "Missing MySQL table: " + table);
            }
            assertFalse(schema.contains("ON CONFLICT"));
            assertFalse(schema.contains("BYTEA"));
            assertFalse(schema.contains("TIMESTAMPTZ"));
        }
    }

    @Test
    void readsCanonicalUuidFromMysqlCharColumn() throws SQLException {
        UUID expected = UUID.fromString("9b9aaf67-3c58-42a9-9b3b-fd7f6b1b4c1d");
        CachedRowSet row = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(1);
        metadata.setColumnName(1, "uuid");
        metadata.setColumnType(1, Types.CHAR);
        row.setMetaData(metadata);
        row.moveToInsertRow();
        row.updateString(1, expected.toString());
        row.insertRow();
        row.moveToCurrentRow();
        row.beforeFirst();
        assertTrue(row.next());
        assertEquals(expected, MySqlJdbcValues.readUuid(row, 1));
        row.close();
    }

    @Test
    void rejectsMalformedUuidStoredInMysqlCharColumn() throws SQLException {
        CachedRowSet row = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(1);
        metadata.setColumnName(1, "uuid");
        metadata.setColumnType(1, Types.CHAR);
        row.setMetaData(metadata);
        row.moveToInsertRow();
        row.updateString(1, "not-a-uuid");
        row.insertRow();
        row.moveToCurrentRow();
        row.beforeFirst();
        assertTrue(row.next());
        assertThrows(SQLException.class, () -> MySqlJdbcValues.readUuid(row, 1));
        row.close();
    }
}
