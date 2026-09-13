-- No current-life backfill for unknown historical assignments.
ALTER TABLE original_fleet_unit
    ADD COLUMN controller_unit_id bigint,
    ADD COLUMN controller_ship_generation bigint,
    ADD CONSTRAINT original_fleet_controller_incarnation_pair CHECK (
        (controller_unit_id IS NULL AND controller_ship_generation IS NULL) OR
        (controller_character_id IS NOT NULL AND controller_unit_id IS NOT NULL
         AND controller_ship_generation IS NOT NULL
         AND controller_unit_id BETWEEN 1 AND 4294967295 AND controller_ship_generation>=0));
