-- NEW_DESIGN. The original 0x0326 TacticalUnitShip record carries a morale byte
-- (+0x04 of the record) that this authority has always served as the constant
-- 100, and the client's own message table names both 0x0409
-- CommandEncourageFlagship and 0x0440 NotifyMoraleDown - so morale is a value
-- that falls and is raised again. The numbers below are authored: the original
-- rates are not recovered.
ALTER TABLE original_grid_unit
    ADD COLUMN morale integer NOT NULL DEFAULT 100 CHECK (morale BETWEEN 0 AND 100);

-- 鼓舞 (0x0409). One row per accepted encouragement, keyed by the request's own
-- fingerprint, so a retransmitted command returns the first outcome instead of
-- charging twice. The morale write, the command-point charge and this row
-- commit together.
CREATE TABLE original_flagship_encourage_command (
    account_id uuid NOT NULL,
    request_fingerprint text NOT NULL,
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL,
    grid_id bigint NOT NULL,
    ship_generation bigint NOT NULL CHECK (ship_generation >= 0),
    source_morale integer NOT NULL CHECK (source_morale BETWEEN 0 AND 100),
    result_morale integer NOT NULL CHECK (result_morale BETWEEN 0 AND 100),
    authority_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, request_fingerprint),
    -- Encouraging never lowers morale.
    CHECK (result_morale > source_morale)
);

CREATE INDEX original_flagship_encourage_command_unit
    ON original_flagship_encourage_command (account_id, unit_id, authority_version DESC);
