-- Unknown legacy snapshot provenance stays unknown; do not stamp current life.
ALTER TABLE original_tactical_corps
    ADD COLUMN controller_unit_id bigint,
    ADD COLUMN ship_generation bigint,
    ADD CONSTRAINT original_corps_incarnation_pair CHECK (
        (controller_unit_id IS NULL AND ship_generation IS NULL) OR
        (controller_unit_id IS NOT NULL AND ship_generation IS NOT NULL
         AND controller_unit_id BETWEEN 1 AND 4294967295 AND ship_generation>=0));
