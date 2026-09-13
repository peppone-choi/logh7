-- Original parentage achievement is a u32, separate from PCP/MCP and outfit merit.
-- No retroactive or authored rewards are assigned by this migration.
ALTER TABLE character ADD COLUMN achievement bigint NOT NULL DEFAULT 0
    CHECK (achievement BETWEEN 0 AND 4294967295);
