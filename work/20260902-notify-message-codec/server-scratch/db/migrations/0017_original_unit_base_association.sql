-- NEW_DESIGN compatibility backfill: preserve the existing authored start
-- at Base1/grid101; the already-supported101->102 WARP notifies Base0.
-- This is not recovered original-server docking history.
ALTER TABLE original_grid_unit
    ADD COLUMN base_id bigint NOT NULL DEFAULT 0
    CHECK (base_id BETWEEN 0 AND 4294967295);

UPDATE original_grid_unit SET base_id = 1 WHERE current_cell_id = 101;

-- Replay must retain the association produced by the historical move.
-- Every pre-migration supported move was101->102 with notified Base0.
ALTER TABLE original_grid_move_command
    ADD COLUMN destination_base_id bigint NOT NULL DEFAULT 0
    CHECK (destination_base_id BETWEEN 0 AND 4294967295);

CREATE OR REPLACE FUNCTION seed_original_minimal_grid_unit()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO original_grid_unit(
        account_id, unit_id, character_id, authority_card_id,
        current_cell_id, authority_version, base_id)
    VALUES (NEW.account_id, 2, NEW.character_id, 39, 101, NEW.authority_version, 1)
    ON CONFLICT (account_id, unit_id) DO NOTHING;
    RETURN NEW;
END;
$$;
