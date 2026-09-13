-- NEW_DESIGN durable own-ship departure intents, separate from original wire.
-- Keep history after a character is removed; ownership is checked against the
-- current original_grid_unit before replay or execution.
CREATE TABLE original_departure_request(
    account_id uuid NOT NULL REFERENCES account(account_id),
    request_fingerprint char(64) NOT NULL,
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL,
    source_base_id bigint NOT NULL,
    ship_generation bigint NOT NULL,
    authority_version bigint NOT NULL,
    PRIMARY KEY(account_id,request_fingerprint)
);
