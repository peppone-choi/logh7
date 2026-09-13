-- Restoration-owned durable controller state; no fabricated controller seed.
CREATE TABLE original_tactical_corps (
    character_id bigint PRIMARY KEY REFERENCES character(character_id),
    format_version integer NOT NULL DEFAULT 1 CHECK (format_version=1),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision>0)
);
