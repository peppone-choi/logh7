-- One-use native probe setup, NOT an observed native 0F1A command or birthplace.
-- The caller binds a cloned data directory, character id, and unique receipt identity.
BEGIN;
DO $setup$
DECLARE
    expected_directory text := current_setting('logh7.setup_expected_directory');
    selected_character bigint := current_setting('logh7.setup_character')::bigint;
    fingerprint text := current_setting('logh7.setup_fingerprint');
    r record;
    next_version bigint;
    canonical text;
BEGIN
    IF lower(replace(current_setting('data_directory'),chr(92),'/')) <>
       lower(replace(expected_directory,chr(92),'/')) THEN
        RAISE EXCEPTION 'NATIVE_SETUP_WRONG_CLUSTER';
    END IF;
    IF fingerprint !~ '^[0-9a-f]{64}$' OR selected_character <= 0 OR
       (SELECT count(*) FROM account) <> 1 OR (SELECT count(*) FROM character) <> 1 THEN
        RAISE EXCEPTION 'NATIVE_SETUP_SCOPE_MISMATCH';
    END IF;
    SELECT c.account_id,c.character_id,c.faction,c.return_base_id,a.authority_version,
           u.unit_id,u.current_cell_id,u.damaged,u.destroyed,u.injury_return_id
      INTO r FROM character c JOIN account a USING(account_id)
        JOIN original_grid_unit u USING(account_id,character_id)
      WHERE c.character_id=selected_character FOR UPDATE OF a,c,u;
    IF NOT FOUND THEN RAISE EXCEPTION 'NATIVE_SETUP_CHARACTER_MISSING'; END IF;
    IF r.faction <> 2 OR r.return_base_id <> 0 OR r.unit_id <> 2 OR
       r.current_cell_id <> 101 OR r.damaged <> 0 OR r.destroyed <> 0 OR r.injury_return_id IS NOT NULL THEN
        RAISE EXCEPTION 'NATIVE_SETUP_STATE_MISMATCH';
    END IF;
    next_version := r.authority_version + 1;
    UPDATE character SET return_base_id=2,authority_version=next_version
      WHERE account_id=r.account_id AND character_id=selected_character;
    INSERT INTO original_return_base_request(account_id,request_fingerprint,character_id,return_base_id,authority_version)
      VALUES(r.account_id,fingerprint,selected_character,2,next_version);
    INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
      VALUES(r.account_id,'character',selected_character::text,'OriginalReturnBaseChanged',
        jsonb_build_object('characterId',selected_character,'previousBaseId',0,'returnBaseId',2,
          'requestFingerprint',fingerprint,'nativeProbeSetup',true,'provenance','NEW_DESIGN approved temporary return planet'),
        next_version);
    canonical := '{"accountId":"' || r.account_id::text || '","authorityVersion":' || next_version::text ||
      ',"characterId":' || selected_character::text || ',"returnBaseId":2,"requestFingerprint":"' || fingerprint || '"}';
    UPDATE account SET authority_version=next_version,
        authority_state_hash=encode(sha256(convert_to(E'logh7-authority-state/v1\n' || canonical,'UTF8')),'hex'),
        updated_at=transaction_timestamp() WHERE account_id=r.account_id;
END
$setup$;
COMMIT;
SELECT 'TEST_RETURN_BASE_SELECTED' AS status,character_id,return_base_id,authority_version
  FROM character WHERE character_id=current_setting('logh7.setup_character')::bigint;
