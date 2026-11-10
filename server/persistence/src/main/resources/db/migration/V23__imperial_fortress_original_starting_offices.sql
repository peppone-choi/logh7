ALTER TABLE original_candidate_profile
    DROP CONSTRAINT original_candidate_profile_lusiana_starting_offices_check,
    ADD CONSTRAINT original_candidate_profile_fortress_starting_offices_check CHECK (
        starting_office IS NULL OR (
            candidate_id BETWEEN 1003 AND 9999 AND (
                (starting_office = 'merkatz-fleet-3' AND rank = 4 AND country_id IS NOT DISTINCT FROM 2)
                OR (rank = 5 AND (
                    (starting_office IN ('reinhard-fleet-2','staden-fleet-4','fogel-fleet-5','hildesheim-fleet-6','landsberg-fleet-8','flegel-fleet-10') AND country_id IS NOT DISTINCT FROM 2)
                    OR (starting_office IN ('vucock-fleet-5','paetta-fleet-2','hawood-fleet-7','lefebvre-fleet-3') AND country_id IS NOT DISTINCT FROM 3)
                ))
            )
        )
    );
