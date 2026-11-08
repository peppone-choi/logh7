ALTER TABLE original_candidate_profile
    ADD COLUMN country_id bigint REFERENCES nation_country(country_id),
    ADD CONSTRAINT original_candidate_country_required CHECK (
        candidate_id IN (1001, 1002) OR (candidate_id >= 1003 AND country_id IS NOT NULL)
    );

CREATE INDEX original_candidate_world_country ON original_candidate_profile (world_identity, country_id);
