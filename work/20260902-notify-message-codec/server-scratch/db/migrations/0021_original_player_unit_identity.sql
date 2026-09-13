-- NEW_DESIGN player-unit identity adapter: the session has always joined
-- character_id == flagship unit_id. The old per-account constant2 trigger
-- violates this for other accounts and silently omits additional slots.
-- Apply with gateway sessions stopped; never hot-renumber native client objects.
LOCK TABLE character IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE original_grid_unit IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    -- NEW_DESIGN namespace: reserve 0x7F000000 and above for authored NPCs
    -- and future non-player entities; includes current0x7F000001/2 identities.
    IF EXISTS (SELECT 1 FROM character WHERE character_id NOT BETWEEN 1 AND 2130706431) THEN
        RAISE EXCEPTION 'PLAYER_UNIT_CHARACTER_ID_RESERVED_OR_OUT_OF_RANGE';
    END IF;
    IF EXISTS (SELECT 1 FROM original_grid_unit WHERE unit_id <> 2 AND unit_id <> character_id) THEN
        RAISE EXCEPTION 'PLAYER_UNIT_NONLEGACY_MAPPING_REQUIRES_REVIEW';
    END IF;
END;
$$;

-- Audit aliases only: do not rewrite historical commands, events or defeat hashes.
CREATE TABLE original_player_unit_identity_migration (
    account_id uuid NOT NULL,
    character_id bigint NOT NULL,
    previous_unit_id bigint,
    unit_id bigint NOT NULL,
    migrated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY(account_id,character_id)
);
INSERT INTO original_player_unit_identity_migration(account_id,character_id,previous_unit_id,unit_id)
SELECT c.account_id,c.character_id,u.unit_id,c.character_id
FROM character c LEFT JOIN original_grid_unit u USING(account_id,character_id)
WHERE u.unit_id IS NULL OR u.unit_id <> c.character_id;

UPDATE original_grid_unit SET unit_id=character_id WHERE unit_id <> character_id;
-- Existing rows retain every loss/base/version field. Only previously missing
-- player units receive the same explicit authored initial state as new accounts.
INSERT INTO original_grid_unit(account_id,unit_id,character_id,authority_card_id,
    current_cell_id,authority_version,base_id)
SELECT c.account_id,c.character_id,c.character_id,39,101,c.authority_version,1
FROM character c
WHERE NOT EXISTS(SELECT 1 FROM original_grid_unit u
    WHERE u.account_id=c.account_id AND u.character_id=c.character_id);

ALTER TABLE original_grid_unit
    ADD CONSTRAINT original_player_unit_global_identity UNIQUE(unit_id),
    ADD CONSTRAINT original_player_unit_character_identity CHECK(unit_id=character_id),
    ADD CONSTRAINT original_player_unit_namespace CHECK(unit_id BETWEEN 1 AND 2130706431);

CREATE OR REPLACE FUNCTION seed_original_minimal_grid_unit()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO original_grid_unit(account_id,unit_id,character_id,authority_card_id,
        current_cell_id,authority_version,base_id)
    VALUES(NEW.account_id,NEW.character_id,NEW.character_id,39,101,NEW.authority_version,1);
    RETURN NEW;
END;
$$;
