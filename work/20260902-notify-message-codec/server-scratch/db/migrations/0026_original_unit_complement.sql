-- Preserve all legacy quantities and casualties. No implicit100->1 conversion.
ALTER TABLE original_grid_unit
    ADD COLUMN unit_number integer NOT NULL DEFAULT 100,
    ADD CONSTRAINT original_grid_unit_number_valid CHECK (unit_number BETWEEN 1 AND 65535),
    ADD CONSTRAINT original_grid_unit_loss_within_number CHECK (damaged <= unit_number);
