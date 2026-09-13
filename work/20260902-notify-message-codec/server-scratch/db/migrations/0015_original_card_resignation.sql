-- 0015: 辞任 (CommandCardResignation 0x0709) persistence.
-- Card 0 is the ORIGINAL's own "holds no post" state, not a new invention: constmsg group 3 row 0 is 個人 and group 4
-- row 0 is 皇宮, and serving card 0 in the world-entry character record makes the unmodified client render
-- 職務権限カード as 「皇宮 ： 個人」 with an empty command grid (proven live, run 20260903T085429Z). So the appointment
-- table must accept card 0 as the post-resignation state.
ALTER TABLE original_character_card DROP CONSTRAINT original_character_card_card_id_check;
ALTER TABLE original_character_card ADD CONSTRAINT original_character_card_card_id_check CHECK (card_id BETWEEN 0 AND 65535);

-- Replay/audit of the resignations themselves. source_card_id is the post the character held when they resigned
-- (the client sends it in the 0x0709 body); the resulting state is always card 0.
CREATE TABLE original_card_resignation_command (
    account_id uuid NOT NULL REFERENCES account(account_id),
    character_id bigint NOT NULL REFERENCES character(character_id),
    source_card_id integer NOT NULL CHECK (source_card_id BETWEEN 1 AND 65535),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, request_fingerprint)
);
