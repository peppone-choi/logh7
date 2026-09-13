-- Restoration-server schema, not a recovered original database layout.
-- Separate ordinary-unit roster: never create a fictional character per unit.
-- No seed rows or changes to existing player flagship records.
CREATE TABLE original_fleet_unit (
    unit_id bigint PRIMARY KEY CHECK (unit_id BETWEEN 1 AND 4294967295),
    outfit_id bigint NOT NULL CHECK (outfit_id BETWEEN 1 AND 4294967295),
    kind integer NOT NULL CHECK (kind BETWEEN 0 AND 65535),
    power integer NOT NULL CHECK (power BETWEEN 0 AND 255),
    camp integer NOT NULL CHECK (camp BETWEEN 0 AND 255),
    grid_id bigint NOT NULL CHECK (grid_id BETWEEN 1 AND 4294967295),
    unit_number integer NOT NULL CHECK (unit_number BETWEEN 1 AND 65535),
    damaged integer NOT NULL DEFAULT 0 CHECK (damaged BETWEEN 0 AND unit_number),
    destroyed integer NOT NULL DEFAULT 0 CHECK (destroyed BETWEEN 0 AND damaged),
    x real NOT NULL, y real NOT NULL, z real NOT NULL, direction real NOT NULL,
    cruising real NOT NULL,
    controller_character_id bigint REFERENCES character(character_id),
    autonomous boolean NOT NULL DEFAULT true,
    ship_generation bigint NOT NULL DEFAULT 0 CHECK (ship_generation >= 0),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CHECK (autonomous OR controller_character_id IS NOT NULL),
    CHECK (x > '-Infinity'::real AND x < 'Infinity'::real),
    CHECK (y > '-Infinity'::real AND y < 'Infinity'::real),
    CHECK (z > '-Infinity'::real AND z < 'Infinity'::real),
    CHECK (direction > '-Infinity'::real AND direction < 'Infinity'::real),
    CHECK (cruising >= 0 AND cruising < 'Infinity'::real)
);

CREATE INDEX original_fleet_unit_grid ON original_fleet_unit(grid_id, outfit_id);
