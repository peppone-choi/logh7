-- Preserve the prior authored flagship projection of100 once, not on reload.
-- This is not a recovered original starting supply balance.
ALTER TABLE original_grid_unit
    ADD COLUMN supplies bigint NOT NULL DEFAULT 100 CHECK (supplies BETWEEN 0 AND 4294967295);
