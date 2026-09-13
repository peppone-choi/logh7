-- A fleet repair may leave its controller flagship unselected.
-- Historical rows selected the flagship; retain their stronger invariant.
ALTER TABLE original_flagship_repair_command
    ADD COLUMN include_flagship boolean NOT NULL DEFAULT true;
ALTER TABLE original_flagship_repair_command
    DROP CONSTRAINT original_flagship_repair_command_check;
ALTER TABLE original_flagship_repair_command
    ADD CONSTRAINT original_repair_selected_damage CHECK (
        (include_flagship AND result_damaged = destroyed) OR
        (NOT include_flagship AND result_damaged = source_damaged
            AND result_supplies = source_supplies));
