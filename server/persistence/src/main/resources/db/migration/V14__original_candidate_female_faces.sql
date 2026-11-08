ALTER TABLE original_candidate_profile
    DROP CONSTRAINT original_candidate_profile_face_check,
    ADD CONSTRAINT original_candidate_profile_native_face_check CHECK (
        face BETWEEN 1 AND 158 OR face BETWEEN 100001 AND 100108
        OR face BETWEEN 10001 AND 10006 OR face BETWEEN 110001 AND 110004
    );
