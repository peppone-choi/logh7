CREATE TABLE original_messenger_message (
    message_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sender_account_id uuid NOT NULL REFERENCES account(account_id),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    sender_character_id bigint NOT NULL CHECK (sender_character_id > 0),
    recipient_character_id bigint NOT NULL CHECK (recipient_character_id > 0),
    message_text text NOT NULL CHECK (length(message_text) BETWEEN 1 AND 511),
    wire_payload bytea NOT NULL CHECK (octet_length(wire_payload) BETWEEN 2 AND 1324),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    sent_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (sender_account_id, request_fingerprint)
);

CREATE INDEX original_messenger_message_sender_pair_idx
    ON original_messenger_message(sender_character_id, recipient_character_id, message_id);

CREATE INDEX original_messenger_message_recipient_pair_idx
    ON original_messenger_message(recipient_character_id, sender_character_id, message_id);
