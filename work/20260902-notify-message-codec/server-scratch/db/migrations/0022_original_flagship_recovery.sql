-- NEW_DESIGN incarnation identity. The published update05 destroyer fallback
-- does not describe server storage. Existing losses are not healed by migration.
ALTER TABLE original_grid_unit
    ADD COLUMN ship_generation bigint NOT NULL DEFAULT 0 CHECK(ship_generation >= 0);

CREATE TABLE original_flagship_recovery (
    account_id uuid NOT NULL,
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL,
    return_id uuid NOT NULL,
    expected_unit_version bigint NOT NULL,
    injury_request_hash char(64) NOT NULL,
    previous_generation bigint NOT NULL CHECK(previous_generation >= 0),
    ship_generation bigint NOT NULL,
    damaged integer NOT NULL,
    destroyed integer NOT NULL,
    fallback_kind integer NOT NULL CHECK(fallback_kind IN (3,93)),
    authority_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY(account_id,character_id,return_id),
    UNIQUE(unit_id,ship_generation),
    CHECK(ship_generation = previous_generation + 1),
    CHECK(damaged > 0 AND destroyed = damaged)
);
