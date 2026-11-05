-- 실제 PostgreSQL에서 공유 정의와 참조/무소속/국가별 재정의를 검사한다. 작성: Peppone Choi (peppone-choi).
BEGIN;
\copy nation_calendar FROM 'config/countries/calendars.csv' WITH (FORMAT csv, HEADER true)
INSERT INTO nation_currency VALUES ('fixture_credit','시험 통화','T',0);
INSERT INTO nation_command_system VALUES ('logh7');
INSERT INTO nation_rank_system VALUES ('empire'),('alliance');
INSERT INTO nation_office_system VALUES ('empire'),('alliance');
INSERT INTO nation_peerage_system VALUES ('empire'),('none');
INSERT INTO nation_ship_style VALUES ('empire','제국계'),('alliance','동맹계');
INSERT INTO nation_asset_profile (profile_key,native_key) VALUES ('empire','native/empire'),('alliance','native/alliance');
CREATE TEMP TABLE family_seed (family_key TEXT,display_name TEXT,calendar_key TEXT,commands TEXT,ranks TEXT,offices TEXT,peerage TEXT,ships TEXT);
\copy family_seed FROM 'config/countries/families.csv' WITH (FORMAT csv, HEADER true)
INSERT INTO nation_family SELECT family_key,display_name,calendar_key,'fixture_credit',commands,ranks,offices,peerage,ships FROM family_seed;
CREATE TEMP TABLE country_seed (id BIGINT,key TEXT,name TEXT,family TEXT,power INTEGER,camp INTEGER,assets TEXT);
\copy country_seed FROM 'config/countries/countries.csv' WITH (FORMAT csv, HEADER true)
INSERT INTO nation_country (country_id,country_key,display_name,family_key,native_power,native_camp,asset_profile)
    SELECT id,key,name,family,power,camp,assets FROM country_seed;
INSERT INTO nation_country (country_id,country_key,display_name,family_key,native_power,native_camp,asset_profile,ship_style_override)
    VALUES (100,'test_new_empire','시험 신제국','new_empire',2,0,'alliance','alliance');
INSERT INTO nation_membership VALUES (42,NULL,0);
INSERT INTO nation_territory VALUES (9999,NULL,42);
INSERT INTO nation_capital VALUES (100,9999);
INSERT INTO nation_money_account VALUES ('character',42,'fixture_credit',100);
DO $$
DECLARE value INTEGER;
BEGIN
    SELECT 800+cal.year_offset INTO value FROM nation_country c JOIN nation_family f USING (family_key)
        JOIN nation_calendar cal USING (calendar_key) WHERE c.country_id=2;
    IF value IS DISTINCT FROM 491 THEN RAISE EXCEPTION 'Old empire changed era by year'; END IF;
    SELECT 799+cal.year_offset INTO value FROM nation_country c JOIN nation_family f USING (family_key)
        JOIN nation_calendar cal USING (calendar_key) WHERE c.country_id=100;
    IF value IS DISTINCT FROM 1 THEN RAISE EXCEPTION 'New empire did not use its family calendar'; END IF;
    IF NOT EXISTS (SELECT 1 FROM nation_country c JOIN nation_family f USING (family_key)
        WHERE c.country_id=100 AND COALESCE(c.ship_style_override,f.ship_style)='alliance') THEN
        RAISE EXCEPTION 'Ship style coupled to family';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM nation_membership WHERE character_id=42 AND country_id IS NULL) THEN
        RAISE EXCEPTION 'Unaffiliated membership lost';
    END IF;
    BEGIN
        INSERT INTO nation_membership VALUES (43,999999,0);
        RAISE EXCEPTION 'Unknown country accepted';
    EXCEPTION WHEN foreign_key_violation THEN NULL; END;
    BEGIN
        INSERT INTO nation_capital VALUES (2,9999);
        RAISE EXCEPTION 'Two countries share one capital';
    EXCEPTION WHEN unique_violation THEN NULL; END;
    BEGIN
        UPDATE nation_money_account SET balance_minor=-1 WHERE owner_id=42;
        RAISE EXCEPTION 'Negative balance accepted';
    EXCEPTION WHEN check_violation THEN NULL; END;
    BEGIN
        INSERT INTO nation_country (country_id,country_key,display_name,family_key,native_power,native_camp,asset_profile)
            VALUES (0,'fake_unaffiliated','무소속','alliance',1,0,'alliance');
        RAISE EXCEPTION 'Unaffiliated became a sovereign country';
    EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
ROLLBACK;
