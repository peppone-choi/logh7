-- Input_CreateCharacter 0x004066F0 carries flagship_type:u8 and flagship_kind:u16.
-- Old rows and lottery candidates without source data remain NULL (unknown).
-- Do not infer a ship from its display name or silently assign model zero.
ALTER TABLE character
    ADD COLUMN flagship_type smallint CHECK (flagship_type BETWEEN 0 AND 255),
    ADD COLUMN flagship_kind integer CHECK (flagship_kind BETWEEN 0 AND 65535),
    ADD CONSTRAINT character_flagship_selection_pair
        CHECK ((flagship_type IS NULL) = (flagship_kind IS NULL));
