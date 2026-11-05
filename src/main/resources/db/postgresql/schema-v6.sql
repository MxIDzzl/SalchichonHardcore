-- PostgreSQL schema v6: durable item-level trade escrow and recovery journal.

CREATE UNIQUE INDEX IF NOT EXISTS idx_trade_sessions_active_player_a
    ON trade_sessions (player_a)
    WHERE status IN ('OPEN','LOCKED','RECOVERY_REQUIRED');

CREATE UNIQUE INDEX IF NOT EXISTS idx_trade_sessions_active_player_b
    ON trade_sessions (player_b)
    WHERE status IN ('OPEN','LOCKED','RECOVERY_REQUIRED');

CREATE TABLE IF NOT EXISTS trade_item_operations (
    operation_id UUID PRIMARY KEY,
    trade_id UUID NOT NULL REFERENCES trade_sessions(trade_id) ON DELETE RESTRICT,
    owner_uuid UUID NOT NULL REFERENCES player_data(uuid),
    recipient_uuid UUID NOT NULL REFERENCES player_data(uuid),
    item_payload BYTEA NOT NULL,
    item_fingerprint CHAR(64) NOT NULL CHECK (length(item_fingerprint) = 64),
    status VARCHAR(24) NOT NULL CHECK (status IN (
        'PREPARED','ESCROWED','DELIVERY_PENDING','DELIVERING','DELIVERED',
        'RETURN_PENDING','RETURNED','RECOVERY_REQUIRED'
    )),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    CHECK (owner_uuid <> recipient_uuid)
);

CREATE INDEX IF NOT EXISTS idx_trade_item_operations_recovery
    ON trade_item_operations (trade_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_trade_item_operations_player_pending
    ON trade_item_operations (recipient_uuid, status)
    WHERE status IN ('DELIVERY_PENDING','DELIVERING','RETURN_PENDING','RECOVERY_REQUIRED');

-- A participant lock is role-independent: the same UUID cannot be active as A
-- in one session and B in another. Creation must insert both rows atomically.
CREATE TABLE IF NOT EXISTS trade_player_locks (
    player_uuid UUID PRIMARY KEY REFERENCES player_data(uuid) ON DELETE RESTRICT,
    trade_id UUID NOT NULL REFERENCES trade_sessions(trade_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trade_id, player_uuid)
);

CREATE INDEX IF NOT EXISTS idx_trade_player_locks_trade
    ON trade_player_locks (trade_id);

-- Backfill active sessions into the role-independent lock table. Duplicate
-- participants across active sessions intentionally fail the migration.
INSERT INTO trade_player_locks (player_uuid, trade_id)
SELECT player_uuid, trade_id
FROM (
    SELECT player_a AS player_uuid, trade_id
    FROM trade_sessions
    WHERE status IN ('OPEN','LOCKED','RECOVERY_REQUIRED')
    UNION ALL
    SELECT player_b AS player_uuid, trade_id
    FROM trade_sessions
    WHERE status IN ('OPEN','LOCKED','RECOVERY_REQUIRED')
) active_participants
