ALTER TABLE original_candidate_profile
    ADD COLUMN starting_office TEXT,
    ADD CONSTRAINT original_candidate_profile_starting_office_check CHECK (
        starting_office IS NULL OR (
            starting_office = 'reinhard-fleet-2' AND country_id IS NOT DISTINCT FROM 2
            AND rank = 5 AND candidate_id BETWEEN 1003 AND 9999
        )
    );

CREATE UNIQUE INDEX original_candidate_profile_starting_office_unique
    ON original_candidate_profile (world_identity, starting_office)
    WHERE starting_office IS NOT NULL;
