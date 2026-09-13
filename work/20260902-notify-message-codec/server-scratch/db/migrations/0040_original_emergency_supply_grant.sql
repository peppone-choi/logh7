-- 緊急補給 (0x0422). constmsg group 0 row 27 is
-- 「[ 緊急補給 ] 緊急補給可能にする 実行待機時間48G秒 実行所要時間0G秒」. The row says what the
-- command does and what it does not: it makes an emergency resupply *possible*,
-- it does not perform one. Its recovered body carries a base and a unit - the
-- same two-word tail 修理 and 補給 use - so the grant is exactly that pair.
--
-- The grant is what 補給 (0x0414) then consults: 補給's own row requires a 補給艦,
-- and a unit the base has granted may be resupplied without one while it is in
-- that base's grid. That is the smallest consumer that keeps 「可能にする」 literal;
-- nothing here refills anything by itself.
--
-- One row per (character, unit, base): granting twice is the same grant, so the
-- insert is idempotent on that key and only moves the authority version forward.
CREATE TABLE original_emergency_supply_grant (
    account_id uuid NOT NULL,
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL CHECK (unit_id > 0),
    base_id bigint NOT NULL CHECK (base_id > 0),
    grid_id bigint NOT NULL,
    authority_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, character_id, unit_id, base_id)
);

CREATE INDEX original_emergency_supply_grant_unit
    ON original_emergency_supply_grant (account_id, character_id, unit_id);
