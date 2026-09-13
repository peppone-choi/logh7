-- 降等 CommandRankDown (0x0706): NEW_DESIGN persistence for one-step demotions issued by an acting character.
-- character_rank_command is constrained to promotions (promoted_rank = source_rank - 1), so demotions get their own
-- replay table. character.rank itself moves up by one (20 = 二等兵 is the lowest served rank).
CREATE TABLE character_rank_down_command (
    account_id uuid NOT NULL REFERENCES account(account_id),
    character_id bigint NOT NULL REFERENCES character(character_id),
    actor_character_id bigint NOT NULL,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    source_rank smallint NOT NULL CHECK (source_rank BETWEEN 1 AND 19),
    demoted_rank smallint NOT NULL CHECK (demoted_rank BETWEEN 2 AND 20),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, request_fingerprint),
    CHECK (demoted_rank = source_rank + 1)
);
