CREATE TABLE original_mail_message (
    mail_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account(account_id),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    sender_character_id bigint NOT NULL,
    recipient_character_id bigint NOT NULL,
    title text NOT NULL CHECK (length(title) BETWEEN 1 AND 63),
    body text NOT NULL CHECK (length(body) BETWEEN 1 AND 2047),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    sent_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (account_id, request_fingerprint)
);

CREATE INDEX original_mail_message_account_recipient_idx
    ON original_mail_message(account_id, recipient_character_id, mail_id);
