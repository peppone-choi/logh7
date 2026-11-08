ALTER TABLE original_candidate_profile
    ADD COLUMN peerage smallint CHECK (peerage IS NULL OR (peerage BETWEEN 1 AND 6 AND country_id IS NOT NULL));
