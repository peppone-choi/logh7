-- NEW_DESIGN persistence for the original 0B00 local-planet travel command.
-- No original wait-unit assumption or initial inventory is installed here.
-- Admission must insert this row and charge CP in the same transaction.
CREATE TABLE original_base_travel_command (
    account_id uuid NOT NULL REFERENCES account(account_id) ON DELETE CASCADE,
    request_fingerprint text NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL CHECK (unit_id BETWEEN 1 AND 4294967295),
    ship_generation bigint NOT NULL CHECK (ship_generation >= 0),
    source_unit_version bigint NOT NULL CHECK (source_unit_version > 0),
    grid_id bigint NOT NULL CHECK (grid_id BETWEEN 1 AND 4294967295),
    source_base_id bigint NOT NULL CHECK (source_base_id BETWEEN 0 AND 4294967295),
    destination_base_id bigint NOT NULL CHECK (destination_base_id BETWEEN 1 AND 4294967295),
    accepted_at timestamptz NOT NULL,
    due_at timestamptz NOT NULL CHECK (due_at >= accepted_at),
    outcome text NOT NULL DEFAULT 'pending' CHECK (outcome IN ('pending','completed','cancelled')),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    finished_at timestamptz,
    completion_authority_version bigint,
    PRIMARY KEY (account_id, request_fingerprint),
    FOREIGN KEY (account_id, character_id)
        REFERENCES character(account_id, character_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id, unit_id)
        REFERENCES original_grid_unit(account_id, unit_id) ON DELETE CASCADE,
    CHECK (source_base_id <> destination_base_id),
    CHECK ((outcome = 'pending' AND finished_at IS NULL AND completion_authority_version IS NULL)
        OR (outcome <> 'pending' AND finished_at IS NOT NULL
            AND completion_authority_version IS NOT NULL
            AND completion_authority_version > authority_version)),
    CHECK (finished_at IS NULL OR finished_at >= accepted_at),
    CHECK (outcome <> 'completed' OR finished_at >= due_at)
);

-- A previous incarnation's pending trip must be cancelled before accepting
-- another trip for the same unit identity; never allow both to complete.
CREATE UNIQUE INDEX original_base_travel_one_pending_unit
    ON original_base_travel_command(account_id, unit_id) WHERE outcome = 'pending';
CREATE INDEX original_base_travel_due
    ON original_base_travel_command(due_at) WHERE outcome = 'pending';
