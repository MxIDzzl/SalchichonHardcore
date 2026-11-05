package com.escuadronsalchichon.hardcore.database;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VaultClaimRecordTest {
    @Test void protectsClaimPayloadFromExternalMutation() {
        byte[] source = {4, 5, 6};
        VaultClaimRecord claim = new VaultClaimRecord(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "DIAMOND", 2, source, "PENDING");
        source[0] = 0;
        assertArrayEquals(new byte[]{4, 5, 6}, claim.payload());
        byte[] copy = claim.payload();
        copy[2] = 0;
        assertArrayEquals(new byte[]{4, 5, 6}, claim.payload());
    }
    private VaultClaimRecord claim(String status) {
        return new VaultClaimRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "DIAMOND", 16, new byte[]{1, 2, 3}, status);
    }

    @Test
    void pendingClaimMayOnlyStartWhenNoDeliveryMarkerExists() {
        assertEquals(VaultClaimRecord.RecoveryAction.DELIVER_PENDING, claim("PENDING").actionFor(0));
        assertEquals(VaultClaimRecord.RecoveryAction.MANUAL_REVIEW, claim("PENDING").actionFor(16));
    }

    @Test
    void interruptedDeliveryIsAcknowledgedOnlyWithExactMarkedAmount() {
        assertEquals(VaultClaimRecord.RecoveryAction.ACKNOWLEDGE_DELIVERY, claim("DELIVERING").actionFor(16));
        assertEquals(VaultClaimRecord.RecoveryAction.ACKNOWLEDGE_DELIVERY, claim("RECOVERY_REQUIRED").actionFor(16));
        assertEquals(VaultClaimRecord.RecoveryAction.MANUAL_REVIEW, claim("RECOVERY_REQUIRED").actionFor(15));
        assertEquals(VaultClaimRecord.RecoveryAction.MANUAL_REVIEW, claim("RECOVERY_REQUIRED").actionFor(0));
    }

}
