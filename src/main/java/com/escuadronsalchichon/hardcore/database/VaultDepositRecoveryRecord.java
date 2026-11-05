package com.escuadronsalchichon.hardcore.database;

import java.util.Objects;

public record VaultDepositRecoveryRecord(VaultItemRecord item, String status) {
    public VaultDepositRecoveryRecord {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(status, "status");
        if (!status.equals("PREPARED") && !status.equals("DELIVERY_PENDING")
                && !status.equals("RECOVERY_REQUIRED")) {
            throw new IllegalArgumentException("Unsupported deposit recovery status");
        }
    }
}
