-- Restoration-server balances. Zero preserves the previous constant-zero
-- projection; no original starting grant or regeneration formula is inferred.
ALTER TABLE character
    ADD COLUMN pcp bigint NOT NULL DEFAULT 0 CHECK (pcp BETWEEN 0 AND 4294967295),
    ADD COLUMN mcp bigint NOT NULL DEFAULT 0 CHECK (mcp BETWEEN 0 AND 4294967295);
