ALTER TABLE character
    ADD CONSTRAINT character_account_character_unique UNIQUE (account_id, character_id);

CREATE TABLE original_grid_unit (
    account_id uuid NOT NULL REFERENCES account(account_id) ON DELETE CASCADE,
    unit_id bigint NOT NULL CHECK (unit_id BETWEEN 1 AND 4294967295),
    character_id bigint NOT NULL,
    authority_card_id integer NOT NULL CHECK (authority_card_id BETWEEN 1 AND 65535),
    current_cell_id bigint NOT NULL CHECK (current_cell_id BETWEEN 1 AND 4294967295),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, unit_id),
    UNIQUE (account_id, character_id),
    FOREIGN KEY (account_id, character_id)
        REFERENCES character(account_id, character_id) ON DELETE CASCADE
);

CREATE TABLE original_grid_move_command (
    account_id uuid NOT NULL REFERENCES account(account_id) ON DELETE CASCADE,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL CHECK (unit_id BETWEEN 1 AND 4294967295),
    authority_card_id integer NOT NULL CHECK (authority_card_id BETWEEN 1 AND 65535),
    expected_current_cell_id bigint NOT NULL CHECK (expected_current_cell_id BETWEEN 1 AND 4294967295),
    source_cell_id bigint NOT NULL CHECK (source_cell_id BETWEEN 1 AND 4294967295),
    destination_cell_id bigint NOT NULL CHECK (destination_cell_id BETWEEN 1 AND 4294967295),
    action integer NOT NULL CHECK (action BETWEEN 1 AND 65535),
    outcome text NOT NULL CHECK (outcome = 'moved'),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, request_fingerprint),
    UNIQUE (account_id, authority_version),
    CHECK (expected_current_cell_id = source_cell_id),
    CHECK (source_cell_id <> destination_cell_id)
);

CREATE INDEX original_grid_move_command_unit_version_idx
    ON original_grid_move_command(account_id, unit_id, authority_version);

CREATE FUNCTION seed_original_minimal_grid_unit()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO original_grid_unit(
        account_id, unit_id, character_id, authority_card_id,
        current_cell_id, authority_version)
    VALUES (NEW.account_id, 2, NEW.character_id, 39, 101, NEW.authority_version)
    ON CONFLICT (account_id, unit_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER character_seed_original_minimal_grid_unit
AFTER INSERT ON character
FOR EACH ROW
EXECUTE FUNCTION seed_original_minimal_grid_unit();

INSERT INTO original_grid_unit(
    account_id, unit_id, character_id, authority_card_id,
    current_cell_id, authority_version)
SELECT ranked.account_id, 2, ranked.character_id, 39, 101, ranked.authority_version
FROM (
    SELECT account_id, character_id, authority_version,
           row_number() OVER (PARTITION BY account_id ORDER BY slot, character_id) AS position
    FROM character
) AS ranked
WHERE ranked.position = 1
ON CONFLICT (account_id, unit_id) DO NOTHING;
