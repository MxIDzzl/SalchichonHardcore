package com.escuadronsalchichon.hardcore.database;

/** Read-only snapshot of durable Player Value operations requiring attention. */
public record VaultRecoverySummary(
        int preparedDeposits,
        int pendingWithdrawals,
        int recoveryRequiredDeposits,
        int recoveryRequiredWithdrawals
) {
    public VaultRecoverySummary {
        if (preparedDeposits < 0 || pendingWithdrawals < 0
                || recoveryRequiredDeposits < 0 || recoveryRequiredWithdrawals < 0) {
            throw new IllegalArgumentException("Recovery counts cannot be negative.");
        }
    }

    public int totalRecoveryRequired() {
        return Math.addExact(recoveryRequiredDeposits, recoveryRequiredWithdrawals);
    }
}
