ALTER TABLE npc_fleet_dialogue ADD COLUMN behavior varchar(16) NOT NULL DEFAULT 'standard'
    CHECK (behavior IN ('standard', 'precision', 'cautious'));
