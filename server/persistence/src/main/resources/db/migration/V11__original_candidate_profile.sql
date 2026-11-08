CREATE TABLE original_candidate_profile (
    world_identity uuid NOT NULL,
    candidate_id integer NOT NULL CHECK (candidate_id BETWEEN 1 AND 9999),
    revision bigint NOT NULL CHECK (revision > 0),
    surname varchar(12) NOT NULL CHECK (length(surname) > 0),
    given_name varchar(12) NOT NULL CHECK (length(given_name) > 0),
    face integer NOT NULL CHECK (face BETWEEN 1 AND 158 OR face BETWEEN 100001 AND 100108),
    rank smallint NOT NULL CHECK (rank BETWEEN 2 AND 20),
    abilities smallint[] NOT NULL CHECK (
        array_ndims(abilities) = 1 AND cardinality(abilities) = 8
        AND array_position(abilities, NULL) IS NULL AND 0 <= ALL(abilities) AND 255 >= ALL(abilities)
    ),
    PRIMARY KEY (world_identity, candidate_id)
);
