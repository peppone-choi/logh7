-- 完全修復 (0x0C00). One row per accepted repair, keyed by the request's own
-- fingerprint, so a retransmitted command returns the first outcome instead of
-- repairing and charging twice. The repair itself, the command-point charge and
-- this row commit together.
CREATE TABLE original_flagship_repair_command (
    account_id uuid NOT NULL,
    request_fingerprint text NOT NULL,
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL,
    grid_id bigint NOT NULL,
    base_id bigint NOT NULL CHECK (base_id > 0),
    ship_generation bigint NOT NULL CHECK (ship_generation >= 0),
    source_damaged integer NOT NULL CHECK (source_damaged >= 0),
    result_damaged integer NOT NULL CHECK (result_damaged >= 0),
    destroyed integer NOT NULL CHECK (destroyed >= 0),
    source_supplies bigint NOT NULL CHECK (source_supplies >= 0),
    result_supplies bigint NOT NULL CHECK (result_supplies >= 0),
    authority_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, request_fingerprint),
    -- A repair never resurrects destroyed hulls: it can only bring the damaged
    -- count down to the destroyed count.
    CHECK (result_damaged = destroyed),
    CHECK (source_damaged >= destroyed)
);

CREATE INDEX original_flagship_repair_command_unit
    ON original_flagship_repair_command (account_id, unit_id, authority_version DESC);
