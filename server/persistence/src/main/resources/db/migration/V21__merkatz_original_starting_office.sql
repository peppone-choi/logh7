ALTER TABLE original_candidate_profile
    DROP CONSTRAINT original_candidate_profile_fogel_starting_office_check,
    ADD CONSTRAINT original_candidate_profile_merkatz_starting_office_check CHECK (
        starting_office IS NULL OR (
            candidate_id BETWEEN 1003 AND 9999 AND (
                (starting_office = 'merkatz-fleet-3' AND rank = 4 AND country_id IS NOT DISTINCT FROM 2)
                OR (rank = 5 AND (
                    (starting_office IN ('reinhard-fleet-2','staden-fleet-4','fogel-fleet-5') AND country_id IS NOT DISTINCT FROM 2)
                    OR (starting_office IN ('vucock-fleet-5','paetta-fleet-2') AND country_id IS NOT DISTINCT FROM 3)
                ))
            )
        )
    );
