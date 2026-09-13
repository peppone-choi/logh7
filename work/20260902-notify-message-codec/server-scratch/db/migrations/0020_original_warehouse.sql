-- NEW_DESIGN authority storage; no original starting stock or role grants are seeded.
-- Warehouses are global objects. An account is an actor, never part of a stock key.
CREATE TABLE original_warehouse (
    base_id bigint NOT NULL CHECK (base_id BETWEEN 1 AND 4294967295),
    outfit_id bigint NOT NULL CHECK (outfit_id BETWEEN 0 AND 4294967295),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (base_id, outfit_id)
);
CREATE TABLE original_warehouse_access (
    base_id bigint NOT NULL,
    outfit_id bigint NOT NULL,
    account_id uuid NOT NULL,
    character_id bigint NOT NULL,
    can_write boolean NOT NULL DEFAULT false,
    PRIMARY KEY (base_id, outfit_id, account_id, character_id),
    FOREIGN KEY (base_id, outfit_id) REFERENCES original_warehouse(base_id, outfit_id),
    FOREIGN KEY (account_id, character_id) REFERENCES character(account_id, character_id) ON DELETE CASCADE
);
CREATE TABLE original_warehouse_balance (
    base_id bigint NOT NULL,
    outfit_id bigint NOT NULL,
    stock_kind smallint NOT NULL CHECK (stock_kind BETWEEN 0 AND 5),
    item_kind integer NOT NULL CHECK (item_kind BETWEEN 0 AND 65535),
    grade smallint NOT NULL CHECK (grade BETWEEN 0 AND 255),
    quantity bigint NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY (base_id, outfit_id, stock_kind, item_kind, grade),
    FOREIGN KEY (base_id, outfit_id) REFERENCES original_warehouse(base_id, outfit_id),
    CHECK ((stock_kind=0 AND quantity<=255)
        OR (stock_kind IN (1,2) AND quantity<=65535)
        OR (stock_kind IN (3,4,5) AND quantity<=4294967295)),
    CHECK (stock_kind=2 OR grade=0),
    CHECK (stock_kind<3 OR item_kind=0)
);
CREATE TABLE original_warehouse_transfer_request (
    account_id uuid NOT NULL REFERENCES account(account_id),
    request_id uuid NOT NULL,
    character_id bigint NOT NULL,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    source_version bigint NOT NULL CHECK (source_version>0),
    destination_version bigint NOT NULL CHECK (destination_version>0),
    authority_version bigint NOT NULL CHECK (authority_version>0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, request_id)
);
