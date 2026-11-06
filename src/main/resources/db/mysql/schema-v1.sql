-- MySQL 8 / MariaDB-compatible baseline schema.
-- UUIDs are canonical lowercase/uppercase-insensitive CHAR(36) values.
-- All DATETIME(6) values are written and interpreted as UTC by the application.
-- This schema is additive and idempotent so an interrupted DDL run can be retried.
-- Player Value remains disabled until the complete JDBC adapter and recovery tests ship.

CREATE TABLE IF NOT EXISTS schema_version (
    version INT NOT NULL PRIMARY KEY,
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_data (
    uuid CHAR(36) NOT NULL PRIMARY KEY,
    username VARCHAR(16) NOT NULL,
    last_death_date DATE NULL,
    totem_uses BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_player_data_totems CHECK (totem_uses >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    target_uuid CHAR(36) NOT NULL,
    target_name VARCHAR(16) NOT NULL,
    admin_name VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_admin_audit_target_created (target_uuid, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_stats (
    player_uuid CHAR(36) NOT NULL PRIMARY KEY,
    official_deaths BIGINT NOT NULL DEFAULT 0,
    playtime_ticks BIGINT NOT NULL DEFAULT 0,
    player_value BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_player_stats_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid) ON DELETE CASCADE,
    CONSTRAINT chk_player_stats_deaths CHECK (official_deaths >= 0),
    CONSTRAINT chk_player_stats_playtime CHECK (playtime_ticks >= 0),
    CONSTRAINT chk_player_stats_value CHECK (player_value >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS stat_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_uuid CHAR(36) NOT NULL,
    stat_key VARCHAR(64) NOT NULL,
    previous_value BIGINT NOT NULL,
    new_value BIGINT NOT NULL,
    actor VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_stat_events_player_created (player_uuid, created_at DESC),
    CONSTRAINT fk_stat_events_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_value_items (
    item_id CHAR(36) NOT NULL PRIMARY KEY,
    player_uuid CHAR(36) NOT NULL,
    material VARCHAR(128) NOT NULL,
    amount INT NOT NULL,
    item_payload LONGBLOB NOT NULL,
    credited_value BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_player_value_items_owner (player_uuid, created_at, item_id),
    CONSTRAINT fk_player_value_items_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid) ON DELETE CASCADE,
    CONSTRAINT chk_player_value_items_amount CHECK (amount > 0),
    CONSTRAINT chk_player_value_items_value CHECK (credited_value >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trade_sessions (
    trade_id CHAR(36) NOT NULL PRIMARY KEY,
    player_a CHAR(36) NOT NULL,
    player_b CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    confirmed_revision_a BIGINT NULL,
    confirmed_revision_b BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    active_player_a CHAR(36) GENERATED ALWAYS AS (CASE WHEN status IN ('OPEN','LOCKED','RECOVERY_REQUIRED') THEN player_a ELSE NULL END) STORED,
    active_player_b CHAR(36) GENERATED ALWAYS AS (CASE WHEN status IN ('OPEN','LOCKED','RECOVERY_REQUIRED') THEN player_b ELSE NULL END) STORED,
    UNIQUE KEY uq_trade_sessions_active_a (active_player_a),
    UNIQUE KEY uq_trade_sessions_active_b (active_player_b),
    INDEX idx_trade_sessions_players (player_a, player_b, status),
    CONSTRAINT fk_trade_sessions_a FOREIGN KEY (player_a) REFERENCES player_data(uuid),
    CONSTRAINT fk_trade_sessions_b FOREIGN KEY (player_b) REFERENCES player_data(uuid),
    CONSTRAINT chk_trade_sessions_revision CHECK (revision >= 0),
    CONSTRAINT chk_trade_sessions_distinct CHECK (player_a <> player_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trade_escrow_items (
    escrow_item_id CHAR(36) NOT NULL PRIMARY KEY,
    trade_id CHAR(36) NOT NULL,
    owner_uuid CHAR(36) NOT NULL,
    recipient_uuid CHAR(36) NOT NULL,
    item_payload LONGBLOB NOT NULL,
    item_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    delivered_at DATETIME(6) NULL,
    INDEX idx_trade_escrow_pending (recipient_uuid, trade_id, delivered_at),
    CONSTRAINT fk_trade_escrow_session FOREIGN KEY (trade_id) REFERENCES trade_sessions(trade_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trade_escrow_owner FOREIGN KEY (owner_uuid) REFERENCES player_data(uuid),
    CONSTRAINT fk_trade_escrow_recipient FOREIGN KEY (recipient_uuid) REFERENCES player_data(uuid),
    CONSTRAINT chk_trade_escrow_distinct CHECK (owner_uuid <> recipient_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS operation_receipts (
    operation_id CHAR(36) NOT NULL PRIMARY KEY,
    player_uuid CHAR(36) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    result_reference CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_operation_receipts_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vault_operations (
    operation_id CHAR(36) NOT NULL PRIMARY KEY,
    player_uuid CHAR(36) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    item_id CHAR(36) NULL,
    material VARCHAR(128) NOT NULL,
    amount INT NOT NULL,
    item_payload LONGBLOB NOT NULL,
    credited_value BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    active_player_uuid CHAR(36) GENERATED ALWAYS AS (CASE WHEN status IN ('PREPARED','DELIVERY_PENDING','RECOVERY_REQUIRED') THEN player_uuid ELSE NULL END) STORED,
    UNIQUE KEY uq_vault_operations_one_active_per_player (active_player_uuid),
    INDEX idx_vault_operations_pending (player_uuid, status, created_at),
    CONSTRAINT fk_vault_operations_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid),
    CONSTRAINT chk_vault_operations_amount CHECK (amount > 0),
    CONSTRAINT chk_vault_operations_value CHECK (credited_value >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vault_claims (
    claim_id CHAR(36) NOT NULL PRIMARY KEY,
    operation_id CHAR(36) NOT NULL UNIQUE,
    player_uuid CHAR(36) NOT NULL,
    item_payload LONGBLOB NOT NULL,
    material VARCHAR(128) NOT NULL,
    amount INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    delivered_at DATETIME(6) NULL,
    INDEX idx_vault_claims_player_pending (player_uuid, status, created_at),
    CONSTRAINT fk_vault_claims_operation FOREIGN KEY (operation_id) REFERENCES vault_operations(operation_id),
    CONSTRAINT fk_vault_claims_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid),
    CONSTRAINT chk_vault_claims_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vault_cooldowns (
    player_uuid CHAR(36) NOT NULL PRIMARY KEY,
    next_allowed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_vault_cooldowns_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_value_adjustments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_uuid CHAR(36) NOT NULL,
    previous_value BIGINT NOT NULL,
    new_value BIGINT NOT NULL,
    adjustment_delta BIGINT NOT NULL,
    actor VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_player_value_adjustments_player (player_uuid, created_at, id),
    CONSTRAINT fk_player_value_adjustments_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid),
    CONSTRAINT chk_player_value_adjustments_previous CHECK (previous_value >= 0),
    CONSTRAINT chk_player_value_adjustments_new CHECK (new_value >= 0),
    CONSTRAINT chk_player_value_adjustments_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_skins (
    player_uuid CHAR(36) NOT NULL PRIMARY KEY,
    texture_hash VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_player_skins_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid) ON DELETE CASCADE,
    CONSTRAINT chk_player_skins_hash CHECK (CHAR_LENGTH(texture_hash) BETWEEN 32 AND 64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trade_item_operations (
    operation_id CHAR(36) NOT NULL PRIMARY KEY,
    trade_id CHAR(36) NOT NULL,
    owner_uuid CHAR(36) NOT NULL,
    recipient_uuid CHAR(36) NOT NULL,
    item_payload LONGBLOB NOT NULL,
    item_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    delivered_at DATETIME(6) NULL,
    INDEX idx_trade_item_operations_recovery (trade_id, status, created_at),
    INDEX idx_trade_item_operations_player_pending (recipient_uuid, status),
    CONSTRAINT fk_trade_item_operations_session FOREIGN KEY (trade_id) REFERENCES trade_sessions(trade_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trade_item_operations_owner FOREIGN KEY (owner_uuid) REFERENCES player_data(uuid),
    CONSTRAINT fk_trade_item_operations_recipient FOREIGN KEY (recipient_uuid) REFERENCES player_data(uuid),
    CONSTRAINT chk_trade_item_operations_fingerprint CHECK (CHAR_LENGTH(item_fingerprint) = 64),
    CONSTRAINT chk_trade_item_operations_distinct CHECK (owner_uuid <> recipient_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trade_player_locks (
    player_uuid CHAR(36) NOT NULL PRIMARY KEY,
    trade_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_trade_player_locks_trade_player (trade_id, player_uuid),
    INDEX idx_trade_player_locks_trade (trade_id),
    CONSTRAINT fk_trade_player_locks_player FOREIGN KEY (player_uuid) REFERENCES player_data(uuid) ON DELETE RESTRICT,
    CONSTRAINT fk_trade_player_locks_session FOREIGN KEY (trade_id) REFERENCES trade_sessions(trade_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS public_clans (
    clan_uuid CHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    tag VARCHAR(32) NOT NULL,
    description TEXT NOT NULL,
    leader_uuid CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    member_count INT NOT NULL,
    max_members INT NOT NULL,
    points BIGINT NOT NULL,
    level INT NOT NULL,
    kills BIGINT NOT NULL,
    deaths BIGINT NOT NULL,
    kdr DOUBLE NOT NULL,
    synced_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_public_clans_points (points DESC, clan_uuid),
    CONSTRAINT chk_public_clans_member_count CHECK (member_count >= 0),
    CONSTRAINT chk_public_clans_max_members CHECK (max_members >= 0),
    CONSTRAINT chk_public_clans_points CHECK (points >= 0),
    CONSTRAINT chk_public_clans_level CHECK (level >= 0),
    CONSTRAINT chk_public_clans_kills CHECK (kills >= 0),
    CONSTRAINT chk_public_clans_deaths CHECK (deaths >= 0),
    CONSTRAINT chk_public_clans_kdr CHECK (kdr >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_clan_memberships (
    player_uuid CHAR(36) NOT NULL PRIMARY KEY,
    clan_uuid CHAR(36) NOT NULL,
    rank_id VARCHAR(32) NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    kills BIGINT NOT NULL,
    deaths BIGINT NOT NULL,
    kdr DOUBLE NOT NULL,
    synced_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_player_clan_memberships_clan (clan_uuid, rank_id, player_uuid),
    CONSTRAINT fk_player_clan_memberships_clan FOREIGN KEY (clan_uuid) REFERENCES public_clans(clan_uuid) ON DELETE CASCADE,
    CONSTRAINT chk_player_clan_memberships_kills CHECK (kills >= 0),
    CONSTRAINT chk_player_clan_memberships_deaths CHECK (deaths >= 0),
    CONSTRAINT chk_player_clan_memberships_kdr CHECK (kdr >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
