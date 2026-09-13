-- Original 0F1A/0323 return_base. Zero preserves an unset preference.
-- This schema is replacement-server design, not recovered original storage.
ALTER TABLE character ADD COLUMN return_base_id bigint NOT NULL DEFAULT 0
    CHECK (return_base_id BETWEEN 0 AND 4294967295);

CREATE TABLE original_return_base_request (
    account_id uuid NOT NULL REFERENCES account(account_id),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    -- Historical receipt survives the existing explicit character-delete flow.
    -- Ownership is checked under the account lock before accepting a request.
    character_id bigint NOT NULL CHECK (character_id > 0),
    return_base_id bigint NOT NULL CHECK (return_base_id BETWEEN 0 AND 4294967295),
    authority_version bigint NOT NULL CHECK (authority_version >= 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, request_fingerprint)
);
