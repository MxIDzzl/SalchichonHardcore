package com.escuadronsalchichon.hardcore.database;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
}
