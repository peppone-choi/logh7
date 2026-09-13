CREATE TABLE original_character_lottery_entry (
    entry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account(account_id),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    candidate_character_ids bigint[] NOT NULL
        CHECK (cardinality(candidate_character_ids) BETWEEN 1 AND 5)
        CHECK (0 < ALL(candidate_character_ids))
        CHECK (4294967295 >= ALL(candidate_character_ids)),
    status text NOT NULL CHECK (status IN ('pending', 'awarded')),
    result_character_id bigint NULL CHECK (result_character_id BETWEEN 1 AND 4294967295),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    submitted_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (account_id, request_fingerprint),
    CHECK ((status = 'pending' AND result_character_id IS NULL) OR
           (status = 'awarded' AND result_character_id IS NOT NULL))
);

CREATE UNIQUE INDEX original_character_lottery_one_pending_per_account
    ON original_character_lottery_entry(account_id)
    WHERE status = 'pending';
