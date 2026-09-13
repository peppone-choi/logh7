-- EncourageBase (0x041D). The client's own logger gives the body - a header and a
-- base, nothing else - and 鼓舞 (0x0409) is the same act aimed at a fleet:
-- constmsg group 0 row 10 is 「[ 鼓舞 ] 味方ユニットの士気値を上げる 実行待機時間48G秒
-- 実行所要時間0G秒」. This is that command's base-side twin.
--
-- A base had no state of its own in this authority: tactical bases are catalog
-- content, so there was nowhere for a base's morale to live. This table is that
-- place, and it is NEW_DESIGN, exactly as the fleet's morale rate and price are:
-- the original states neither for either command. The choices here are the
-- symmetric ones rather than new inventions - the same ceiling of 100 the 0x0326
-- record has always been served at, and the same military-pool price the fleet's
-- 鼓舞 already pays.
--
-- A base with no row yet is at that ceiling, so an untouched world needs no
-- backfill and 鼓舞 on a full base is refused rather than charged.
CREATE TABLE original_base_morale (
    account_id uuid NOT NULL,
    base_id bigint NOT NULL CHECK (base_id > 0),
    grid_id bigint NOT NULL,
    morale smallint NOT NULL CHECK (morale >= 0 AND morale <= 100),
    authority_version bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, base_id)
);

-- One row per accepted encouragement, keyed by the request's own fingerprint, so
-- a retransmitted command returns the first outcome instead of charging twice.
CREATE TABLE original_base_encourage_command (
    account_id uuid NOT NULL,
    request_fingerprint text NOT NULL,
    character_id bigint NOT NULL,
    base_id bigint NOT NULL CHECK (base_id > 0),
    grid_id bigint NOT NULL,
    source_morale smallint NOT NULL CHECK (source_morale >= 0),
    result_morale smallint NOT NULL CHECK (result_morale >= 0),
    authority_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, request_fingerprint),
    -- An encouragement only ever raises morale.
    CHECK (result_morale >= source_morale)
);
