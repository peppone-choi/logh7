-- NEW_DESIGN storage of the original InformationUnit mode byte.
-- Preserve the previously served legacy value0; do not infer past departures.
ALTER TABLE original_grid_unit ADD COLUMN mode integer NOT NULL DEFAULT 0
    CHECK (mode BETWEEN 0 AND 255);
ALTER TABLE original_grid_move_command ADD COLUMN result_mode integer NOT NULL DEFAULT 0
    CHECK (result_mode BETWEEN 0 AND 255);
