-- PostgreSQL schema v7: read-only public projection of the authoritative zMClans plugin.
-- SalchichonHardcore never manages clans; it refreshes this cache from zMClans' public API.

CREATE TABLE IF NOT EXISTS public_clans (
    clan_uuid UUID PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    tag VARCHAR(32) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    leader_uuid UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    member_count INTEGER NOT NULL CHECK (member_count >= 0),
    max_members INTEGER NOT NULL CHECK (max_members >= 0),
    points BIGINT NOT NULL CHECK (points >= 0),
    level INTEGER NOT NULL CHECK (level >= 0),
    kills BIGINT NOT NULL CHECK (kills >= 0),
    deaths BIGINT NOT NULL CHECK (deaths >= 0),
    kdr DOUBLE PRECISION NOT NULL CHECK (kdr >= 0),
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_public_clans_points
    ON public_clans (points DESC, clan_uuid);

CREATE TABLE IF NOT EXISTS player_clan_memberships (
    player_uuid UUID PRIMARY KEY,
    clan_uuid UUID NOT NULL REFERENCES public_clans(clan_uuid) ON DELETE CASCADE,
    rank_id VARCHAR(32) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    kills BIGINT NOT NULL CHECK (kills >= 0),
    deaths BIGINT NOT NULL CHECK (deaths >= 0),
    kdr DOUBLE PRECISION NOT NULL CHECK (kdr >= 0),
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_player_clan_memberships_clan
    ON player_clan_memberships (clan_uuid, rank_id, player_uuid);
