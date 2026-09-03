-- 0014: 罷免 (CommandCardDismissal 0x0708) persistence — the inverse of 任命 (0012).
-- The unmodified client sends 0x0708 with the acting character (appointer), the target character being dismissed and
-- the post (static card id). The authority removes the target's current appointment (original_character_card row) and
-- records the dismissal for replay. Original server-side DB semantics (MCP cost, wait time) are not recoverable; this
-- table is the authority state. Captured live 2026-09-03 (run 20260903T065309Z):
--   0708 00000000 00000002 00000000 00000000 00000002 00000028 00000000 00
--   = [u16 type][u32 time][u32 appointer][u32 pcp][u32 mcp][u32 target][u32 cardId][u32 0][u8 0]
CREATE TABLE original_card_dismissal_command (
    account_id uuid NOT NULL REFERENCES account(account_id),
    character_id bigint NOT NULL REFERENCES character(character_id),
    card_id integer NOT NULL CHECK (card_id BETWEEN 1 AND 65535),
    target_character_id bigint NOT NULL REFERENCES character(character_id),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, request_fingerprint)
);
