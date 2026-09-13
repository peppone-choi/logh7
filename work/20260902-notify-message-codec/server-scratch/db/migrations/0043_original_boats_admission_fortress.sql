-- The last three tactical commands that needed state of their own: 空戦 (0x040E),
-- Admission / AdmissionBase (0x040B / 0x041A) and MoveFortress (0x041F).
--
-- Each table is the smallest thing its command needs, and each reuses a shape this
-- authority already has rather than inventing a new one.

-- 空戦. constmsg group 0 row 15 is 「[ 空戦 ] 空戦を仕掛ける 実行待機時間48G秒
-- 実行所要時間0G秒」, and row 34 「[ 対空 ] 空戦隊への攻撃」 names the squadrons it sends.
-- The carried-craft concept is recovered - OriginalStockKind.ShipBoats is one of
-- the warehouse's own stock kinds - but stock is held per base and outfit, so a
-- unit had nowhere to carry its own, exactly as it had nowhere to carry troops.
-- This is that endpoint, and the complement is the same 100 the authority already
-- uses for a unit's stores and its landing force rather than a third magnitude.
CREATE TABLE original_unit_boat (
    account_id uuid NOT NULL,
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL CHECK (unit_id > 0),
    boats bigint NOT NULL CHECK (boats >= 0),
    authority_version bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, character_id, unit_id)
);

-- Admission (0x040B) and AdmissionBase (0x041A). Both loggers print a host - a
-- unit for the first, a base for the second - and a target list, each with its own
-- 「target_size[%d] is over than 32」 bounds check. An admission is a standing
-- permission, so it is stored the way 緊急補給's grant is: one row per pair, and
-- granting the same pair twice is the same grant.
--
-- host_kind separates the two id spaces, which collide: a grid's base 2 beside the
-- player's unit 2.
CREATE TABLE original_admission_grant (
    account_id uuid NOT NULL,
    character_id bigint NOT NULL,
    host_kind smallint NOT NULL CHECK (host_kind IN (0, 1)),   -- 0 unit, 1 base
    host_id bigint NOT NULL CHECK (host_id > 0),
    target_unit_id bigint NOT NULL CHECK (target_unit_id > 0),
    grid_id bigint NOT NULL,
    authority_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, character_id, host_kind, host_id, target_unit_id)
);

-- MoveFortress (0x041F). Its logger prints base, velocity and a to_position[] of
-- {x,y,z} - 平行移動's shape with a base in front of it - so a fortress that has
-- moved needs a position of its own, since the catalog's is where it started.
-- A base with no row here still stands where the content put it.
CREATE TABLE original_base_position (
    account_id uuid NOT NULL,
    base_id bigint NOT NULL CHECK (base_id > 0),
    grid_id bigint NOT NULL,
    x double precision NOT NULL,
    y double precision NOT NULL,
    z double precision NOT NULL,
    authority_version bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, base_id)
);
