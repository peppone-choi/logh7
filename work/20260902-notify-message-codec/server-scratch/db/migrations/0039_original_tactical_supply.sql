-- 補給 (0x0414). constmsg group 0 row 26 is
-- 「[ 補給 ] 補給艦による補給を行う 注）旗艦の左 実行待機時間48G秒 実行所要時間1800G秒」: a 補給艦
-- refills another unit's stores in the field. One row per accepted supply, keyed
-- by the request's own fingerprint, so a retransmitted command returns the first
-- outcome instead of refilling twice. The refill and this row commit together.
--
-- The full stock is 100, which is the value every unit this authority creates
-- starts at (original_grid_unit.supplies defaults to it and the repair primitive
-- spends it to 0); it is not a number invented for this command. The original
-- states no point price for 補給 - the manual's 160 belongs to 完全修復 - so none is
-- charged, and the cost the original does state is the 1800 G秒 the unit is
-- occupied, which the authority enforces from the same tooltip row.
CREATE TABLE original_tactical_supply_command (
    account_id uuid NOT NULL,
    request_fingerprint text NOT NULL,
    character_id bigint NOT NULL,
    unit_id bigint NOT NULL,
    vessel_unit_id bigint NOT NULL CHECK (vessel_unit_id > 0),
    grid_id bigint NOT NULL,
    ship_generation bigint NOT NULL CHECK (ship_generation >= 0),
    source_supplies bigint NOT NULL CHECK (source_supplies >= 0),
    result_supplies bigint NOT NULL CHECK (result_supplies >= 0),
    authority_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, request_fingerprint),
    -- A supply only ever adds stores.
    CHECK (result_supplies >= source_supplies)
);

CREATE INDEX original_tactical_supply_command_unit
    ON original_tactical_supply_command (account_id, unit_id, authority_version DESC);
