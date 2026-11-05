-- PostgreSQL schema v5: cached texture hashes from the installed SkinsRestorer API.
-- Only public skin texture identifiers are stored; no signed property or credentials.

CREATE TABLE IF NOT EXISTS player_skins (
    player_uuid UUID PRIMARY KEY REFERENCES player_data(uuid) ON DELETE CASCADE,
    texture_hash VARCHAR(64) NOT NULL CHECK (length(texture_hash) BETWEEN 32 AND 64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
