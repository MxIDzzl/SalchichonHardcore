package com.escuadronsalchichon.hardcore.database;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultDepositRecoveryRecordTest {
    private VaultDepositRecoveryRecord record(String status) {
        VaultItemRecord item = new VaultItemRecord(UUID.randomUUID(), UUID.randomUUID(),
                "DIAMOND", 1, new byte[]{1, 2, 3}, 10L, Instant.EPOCH);
        return new VaultDepositRecoveryRecord(item, status);
    }

    @Test
    void markerPresentCancelsOnlyBeforeRemovalWasDurablyRecorded() {
        assertEquals(VaultDepositRecoveryRecord.RecoveryAction.CANCEL_AND_KEEP_ITEM,
                record("PREPARED").actionFor(true));
        assertEquals(VaultDepositRecoveryRecord.RecoveryAction.CANCEL_AND_KEEP_ITEM,
                record("RECOVERY_REQUIRED").actionFor(true));
        assertEquals(VaultDepositRecoveryRecord.RecoveryAction.MANUAL_REVIEW,
                record("DELIVERY_PENDING").actionFor(true));
    }

    @Test
    void markerAbsentCommitsOnlyAfterRemovalWasDurablyRecorded() {
        assertEquals(VaultDepositRecoveryRecord.RecoveryAction.COMMIT_REMOVAL,
                record("DELIVERY_PENDING").actionFor(false));
        assertEquals(VaultDepositRecoveryRecord.RecoveryAction.MANUAL_REVIEW,
                record("PREPARED").actionFor(false));
        assertEquals(VaultDepositRecoveryRecord.RecoveryAction.MANUAL_REVIEW,
                record("RECOVERY_REQUIRED").actionFor(false));
    }

    @Test
    void rejectsTerminalOrUnknownStatuses() {
        assertThrows(IllegalArgumentException.class, () -> record("COMMITTED"));
        assertThrows(IllegalArgumentException.class, () -> record("CANCELLED"));
    }
}
