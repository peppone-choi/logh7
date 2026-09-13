-- 陸戦 (0x040F) and 陸戦解除 (0x0410). constmsg group 0 rows 17 and 18 are
-- 「[ 陸戦 ] 碇泊状態から陸戦を投下する 実行待機時間48G秒 実行所要時間240G秒」 and
-- 「[ 陸戦解除 ] 陸戦ユニットを帰還させる 実行待機時間48G秒 実行所要時間240G秒」: from the
-- anchored state a unit drops its landing force onto the base it is anchored at,
-- and the second command brings it back.
--
-- The troop *stock* concept is recovered - OriginalStockKind.Troops, the
-- warehouse transfer primitive keyed by (base, outfit), and the party record's
-- TroopPackages / TroopTransportPackageEmpty / Carrying fields - but stock is held
-- per base and outfit, so neither command had an endpoint on the unit side. These
-- two tables are that endpoint, and they are NEW_DESIGN: nothing recovered says
-- how many troops a unit carries or what a landed force then does.
--
-- The complement is deliberately the same 100 the authority already uses for a
-- unit's full stores, so no second magic number is introduced. A unit with no row
-- yet is carrying that full complement; the row is materialised the first time it
-- lands or recalls, which keeps existing worlds working without a backfill.
CREATE TABLE original_unit_troop (
    account_id uuid NOT NULL,
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL CHECK (unit_id > 0),
    troops bigint NOT NULL CHECK (troops >= 0),
    authority_version bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, character_id, unit_id)
);

-- One landing force, at the base it was dropped on, still owned by the unit that
-- dropped it - which is what 陸戦解除 needs in order to bring the right force home.
CREATE TABLE original_landed_troop (
    account_id uuid NOT NULL,
    character_id bigint NOT NULL,
    base_id bigint NOT NULL CHECK (base_id > 0),
    unit_id bigint NOT NULL CHECK (unit_id > 0),
    grid_id bigint NOT NULL,
    troops bigint NOT NULL CHECK (troops > 0),
    authority_version bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, character_id, base_id, unit_id)
);

CREATE INDEX original_landed_troop_unit
    ON original_landed_troop (account_id, character_id, unit_id);
