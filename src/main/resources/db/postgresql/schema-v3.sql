-- PostgreSQL schema v3: recoverable Player Value vault operations.
-- A withdrawal is committed as a durable claim before any Bukkit inventory delivery.
-- A claim must never be deleted merely because a delivery attempt was interrupted.

CREATE TABLE IF NOT EXISTS vault_operations (
    operation_id UUID PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES player_data(uuid),
    operation_type VARCHAR(16) NOT NULL CHECK (operation_type IN ('DEPOSIT','WITHDRAW')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PREPARED','COMMITTED','DELIVERY_PENDING','DELIVERED','CANCELLED','RECOVERY_REQUIRED')),
    item_id UUID,
    material VARCHAR(128) NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    item_payload BYTEA NOT NULL,
    credited_value BIGINT NOT NULL CHECK (credited_value >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vault_operations_pending
    ON vault_operations (player_uuid, created_at)
    WHERE status IN ('PREPARED','DELIVERY_PENDING','RECOVERY_REQUIRED');

CREATE TABLE IF NOT EXISTS vault_claims (
    claim_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE REFERENCES vault_operations(operation_id),
    player_uuid UUID NOT NULL REFERENCES player_data(uuid),
    item_payload BYTEA NOT NULL,
    material VARCHAR(128) NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','DELIVERING','DELIVERED','RECOVERY_REQUIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_vault_claims_player_pending
    ON vault_claims (player_uuid, created_at)
    WHERE status IN ('PENDING','DELIVERING','RECOVERY_REQUIRED');

CREATE TABLE IF NOT EXISTS vault_cooldowns (
    player_uuid UUID PRIMARY KEY REFERENCES player_data(uuid) ON DELETE CASCADE,
    next_allowed_at TIMESTAMPTZ NOT NULL
);
