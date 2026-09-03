CREATE TABLE original_order_suggest_reply (
    account_id uuid NOT NULL REFERENCES account(account_id) ON DELETE CASCADE,
    character_id bigint NOT NULL REFERENCES character(character_id) ON DELETE CASCADE,
    card_id integer NOT NULL CHECK (card_id > 0),
    reply_value smallint NOT NULL CHECK (reply_value BETWEEN 0 AND 2),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    responded_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, character_id, card_id)
);
