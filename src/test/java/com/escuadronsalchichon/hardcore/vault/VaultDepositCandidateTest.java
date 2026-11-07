package com.escuadronsalchichon.hardcore.vault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultDepositCandidateTest {
    @Test
    void rejectsSlotsOutsidePlayerStorageAndNonPositiveCreditBeforeReadingItems() {
        assertThrows(IllegalArgumentException.class,
                () -> new VaultDepositCandidate(36, null, 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> new VaultDepositCandidate(0, null, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new VaultDepositCandidate(-1, null, 10, null));
    }
}
