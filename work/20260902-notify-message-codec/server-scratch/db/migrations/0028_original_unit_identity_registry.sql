-- One shared ID claim for player flagships and ordinary fleet units.
-- Claims survive row deletion: an old player ID is never repurposed as a fleet ID.
CREATE TABLE original_unit_identity (
    unit_id bigint PRIMARY KEY CHECK (unit_id BETWEEN 1 AND 4294967295),
    owner_kind text NOT NULL CHECK (owner_kind IN ('player', 'fleet'))
);

-- Existing collisions abort the migration; do not renumber or delete user data.
INSERT INTO original_unit_identity(unit_id,owner_kind)
    SELECT unit_id,'player' FROM original_grid_unit
    UNION ALL SELECT unit_id,'fleet' FROM original_fleet_unit;

CREATE FUNCTION claim_original_unit_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE claimed bigint;
BEGIN
    INSERT INTO original_unit_identity(unit_id,owner_kind)
        VALUES(NEW.unit_id,TG_ARGV[0])
        ON CONFLICT(unit_id) DO UPDATE SET unit_id=EXCLUDED.unit_id
        WHERE original_unit_identity.owner_kind=EXCLUDED.owner_kind
        RETURNING unit_id INTO claimed;
    IF claimed IS NULL THEN
        RAISE EXCEPTION 'Original unit ID % is already claimed by another unit category', NEW.unit_id
            USING ERRCODE='23505', CONSTRAINT='original_unit_identity_namespace';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER original_player_identity_claim
    BEFORE INSERT OR UPDATE OF unit_id ON original_grid_unit
    FOR EACH ROW EXECUTE FUNCTION claim_original_unit_identity('player');
CREATE TRIGGER original_fleet_identity_claim
    BEFORE INSERT OR UPDATE OF unit_id ON original_fleet_unit
    FOR EACH ROW EXECUTE FUNCTION claim_original_unit_identity('fleet');
