ALTER TABLE character
    ADD COLUMN rank smallint NOT NULL DEFAULT 20 CHECK (rank BETWEEN 1 AND 20);

CREATE TABLE character_rank_command (
    account_id uuid NOT NULL REFERENCES account(account_id),
    character_id bigint NOT NULL REFERENCES character(character_id),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    source_rank smallint NOT NULL CHECK (source_rank BETWEEN 2 AND 20),
    promoted_rank smallint NOT NULL CHECK (promoted_rank BETWEEN 1 AND 19),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, request_fingerprint),
    CHECK (promoted_rank = source_rank - 1)
);
