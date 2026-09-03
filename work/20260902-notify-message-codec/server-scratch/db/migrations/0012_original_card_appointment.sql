-- 0012: 任命 (CommandCardAppointment 0x0707) persistence.
-- NEW_DESIGN persistence for an ORIGINAL command: the unmodified client sends 0x0707 with the appointing character,
-- the post (static card id) and the appointed character; the authority records the appointment and the appointed
-- character's current post. Original server-side DB semantics are not recoverable; this table is the authority state.
CREATE TABLE original_card_appointment (
    account_id uuid NOT NULL REFERENCES account(account_id),
    character_id bigint NOT NULL REFERENCES character(character_id),
    card_id integer NOT NULL CHECK (card_id BETWEEN 1 AND 65535),
    target_character_id bigint NOT NULL REFERENCES character(character_id),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, request_fingerprint)
);

CREATE TABLE original_character_card (
    account_id uuid NOT NULL REFERENCES account(account_id),
    character_id bigint NOT NULL REFERENCES character(character_id),
    card_id integer NOT NULL CHECK (card_id BETWEEN 1 AND 65535),
    appointed_by_character_id bigint NOT NULL REFERENCES character(character_id),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, character_id)
);
