-- NEW_DESIGN persistent cruising for the authored adjacent 101 <-> 102 route.
-- Existing rows retain the projection previously served by 0325. This is not
-- recovered original fuel history. No casualties or ship generations change.
ALTER TABLE original_grid_unit ADD COLUMN cruising real NOT NULL DEFAULT 10;
UPDATE original_grid_unit SET cruising = CASE WHEN current_cell_id=102 THEN 9 ELSE 10 END;
ALTER TABLE original_grid_unit ADD CONSTRAINT original_grid_cruising_valid
    CHECK (cruising >= 0 AND cruising <= 65535);

-- Pre-migration successful commands supported only 101 -> 102 with cruise 9.
-- Their historical generation was not captured; zero is the legacy baseline.
ALTER TABLE original_grid_move_command ADD COLUMN result_cruising real NOT NULL DEFAULT 9;
ALTER TABLE original_grid_move_command ADD COLUMN ship_generation bigint NOT NULL DEFAULT 0;
ALTER TABLE original_grid_move_command ADD COLUMN damaged integer NOT NULL DEFAULT 0;
ALTER TABLE original_grid_move_command ADD COLUMN destroyed integer NOT NULL DEFAULT 0;
ALTER TABLE original_grid_move_command ADD CONSTRAINT original_grid_move_loss_valid
    CHECK (damaged >= 0 AND damaged <= 65535 AND destroyed >= 0 AND destroyed <= damaged);
ALTER TABLE original_grid_move_command ADD CONSTRAINT original_grid_move_cruising_valid
    CHECK (result_cruising >= 0 AND result_cruising <= 65535);
ALTER TABLE original_grid_move_command ADD CONSTRAINT original_grid_move_generation_valid
    CHECK (ship_generation >= 0);
