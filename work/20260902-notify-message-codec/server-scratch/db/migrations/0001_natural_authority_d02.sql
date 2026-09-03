CREATE TABLE IF NOT EXISTS schema_migration (
    version text PRIMARY KEY,
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    applied_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);

CREATE TABLE account (
    account_id uuid PRIMARY KEY,
    normalized_login text NOT NULL UNIQUE,
    password_hash bytea NOT NULL CHECK (octet_length(password_hash) = 32),
    password_salt bytea NOT NULL CHECK (octet_length(password_salt) = 16),
    argon_memory_kib integer NOT NULL CHECK (argon_memory_kib > 0),
    argon_iterations integer NOT NULL CHECK (argon_iterations > 0),
    argon_parallelism integer NOT NULL CHECK (argon_parallelism > 0),
    status text NOT NULL CHECK (status IN ('active', 'suspended')),
    authority_version bigint NOT NULL DEFAULT 0 CHECK (authority_version >= 0),
    authority_state_hash char(64) NOT NULL CHECK (authority_state_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CHECK (normalized_login ~ '^[a-z0-9][a-z0-9_-]{2,29}$')
);

CREATE TABLE character (
    character_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account(account_id),
    slot smallint NOT NULL CHECK (slot BETWEEN 0 AND 1),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    faction smallint NOT NULL,
    blood smallint NOT NULL,
    sex smallint NOT NULL,
    last_name text NOT NULL CHECK (char_length(last_name) BETWEEN 1 AND 13),
    first_name text NOT NULL CHECK (char_length(first_name) BETWEEN 1 AND 13),
    flagship_name text NOT NULL CHECK (char_length(flagship_name) BETWEEN 0 AND 13),
    face integer NOT NULL,
    ability_values smallint[] NOT NULL CHECK (cardinality(ability_values) = 8),
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (account_id, slot),
    UNIQUE (account_id, request_fingerprint)
);

CREATE TABLE domain_event (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account(account_id),
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    authority_version bigint NOT NULL CHECK (authority_version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (account_id, authority_version)
);
