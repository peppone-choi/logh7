-- Restoration-server state, not recovered original schema or starting balance.
-- Existing ordinary units were projected with authored Supplies=100; preserve
-- that value once during migration instead of resetting it on each scene load.
ALTER TABLE original_fleet_unit
    ADD COLUMN supplies bigint NOT NULL DEFAULT 100
    CHECK (supplies BETWEEN 0 AND 4294967295);
