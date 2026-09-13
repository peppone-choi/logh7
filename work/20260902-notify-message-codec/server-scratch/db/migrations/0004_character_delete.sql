CREATE TABLE character_delete_command (
    account_id uuid NOT NULL REFERENCES account(account_id),
    character_id bigint NOT NULL,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    source_slot smallint NOT NULL CHECK (source_slot BETWEEN 0 AND 1),
    session_id bigint NOT NULL CHECK (session_id BETWEEN 1 AND 4294967295),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, request_fingerprint)
);
