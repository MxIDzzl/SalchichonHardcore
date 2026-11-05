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
    public RecoveryAction actionFor(boolean markerPresent) {
        if (markerPresent && (status.equals("PREPARED") || status.equals("RECOVERY_REQUIRED"))) {
            return RecoveryAction.CANCEL_AND_KEEP_ITEM;
        }
        if (!markerPresent && status.equals("DELIVERY_PENDING")) {
            return RecoveryAction.COMMIT_REMOVAL;
        }
        return RecoveryAction.MANUAL_REVIEW;
    }

    public enum RecoveryAction {
        CANCEL_AND_KEEP_ITEM,
        COMMIT_REMOVAL,
        MANUAL_REVIEW
    }
}
