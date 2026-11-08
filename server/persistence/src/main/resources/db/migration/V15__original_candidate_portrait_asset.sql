ALTER TABLE nation_asset
    DROP CONSTRAINT nation_asset_asset_kind_check,
    ADD CONSTRAINT nation_asset_asset_kind_check CHECK (
        asset_kind IN ('emblem','flag','map_icon','ship_model','ship_texture','portrait')
    );

ALTER TABLE original_candidate_profile
    ADD COLUMN portrait_asset TEXT REFERENCES nation_image_current(asset_key);
