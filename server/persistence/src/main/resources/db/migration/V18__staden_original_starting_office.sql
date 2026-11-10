ALTER TABLE original_candidate_profile
    DROP CONSTRAINT original_candidate_profile_starting_offices_check,
    ADD CONSTRAINT original_candidate_profile_staden_starting_office_check CHECK (
        starting_office IS NULL OR (
            rank = 5 AND candidate_id BETWEEN 1003 AND 9999 AND (
                (starting_office IN ('reinhard-fleet-2','staden-fleet-4') AND country_id IS NOT DISTINCT FROM 2)
                OR (starting_office = 'vucock-fleet-5' AND country_id IS NOT DISTINCT FROM 3)
            )
        )
    );
