-- Replacement-server durable loss/return state; no original injury enum is inferred.
ALTER TABLE original_grid_unit
    ADD COLUMN damaged integer NOT NULL DEFAULT 0 CHECK (damaged BETWEEN 0 AND 65535),
    ADD COLUMN destroyed integer NOT NULL DEFAULT 0 CHECK (destroyed BETWEEN 0 AND 65535),
    ADD COLUMN injury_return_id uuid,
    ADD COLUMN injury_return_request_hash char(64) CHECK (injury_return_request_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT original_grid_unit_return_identity CHECK (
        (injury_return_id IS NULL) = (injury_return_request_hash IS NULL)),
    ADD CONSTRAINT original_grid_unit_casualty_order CHECK (destroyed <= damaged);
