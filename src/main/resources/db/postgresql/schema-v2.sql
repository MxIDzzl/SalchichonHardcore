-- PostgreSQL schema v2: durable vault and trade escrow foundations.
-- Item payloads are serialized server-side Bukkit ItemStacks; never accept client payloads.

CREATE TABLE IF NOT EXISTS player_value_items (
    item_id UUID PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES player_data(uuid) ON DELETE CASCADE,
    material VARCHAR(128) NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    item_payload BYTEA NOT NULL,
    credited_value BIGINT NOT NULL CHECK (credited_value >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_player_value_items_owner
    ON player_value_items (player_uuid, created_at, item_id);

CREATE TABLE IF NOT EXISTS trade_sessions (
    trade_id UUID PRIMARY KEY,
    player_a UUID NOT NULL REFERENCES player_data(uuid),
    player_b UUID NOT NULL REFERENCES player_data(uuid),
    status VARCHAR(24) NOT NULL CHECK (status IN ('OPEN','LOCKED','COMMITTED','CANCELLED','EXPIRED','RECOVERY_REQUIRED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    confirmed_revision_a BIGINT,
    confirmed_revision_b BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (player_a <> player_b)
);

CREATE INDEX IF NOT EXISTS idx_trade_sessions_players
    ON trade_sessions (player_a, player_b, status);

CREATE TABLE IF NOT EXISTS trade_escrow_items (
    escrow_item_id UUID PRIMARY KEY,
    trade_id UUID NOT NULL REFERENCES trade_sessions(trade_id) ON DELETE RESTRICT,
    owner_uuid UUID NOT NULL REFERENCES player_data(uuid),
    recipient_uuid UUID NOT NULL REFERENCES player_data(uuid),
    item_payload BYTEA NOT NULL,
    item_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    CHECK (owner_uuid <> recipient_uuid)
);

CREATE INDEX IF NOT EXISTS idx_trade_escrow_pending
    ON trade_escrow_items (recipient_uuid, trade_id)
    WHERE delivered_at IS NULL;

CREATE TABLE IF NOT EXISTS operation_receipts (
    operation_id UUID PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES player_data(uuid),
    operation_type VARCHAR(32) NOT NULL,
    result_reference UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
