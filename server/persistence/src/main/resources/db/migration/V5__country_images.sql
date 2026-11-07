-- Uploaded PNG originals and immutable edit history. Author: Peppone Choi (peppone-choi).
CREATE TABLE nation_image_version (
    asset_key TEXT NOT NULL REFERENCES nation_asset,
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 2147483647),
    title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 80),
    description TEXT NOT NULL CHECK (length(description) <= 1000),
    sha256 TEXT NOT NULL CHECK (length(sha256)=64),
    width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 2048),
    height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 2048),
    source_png BYTEA NOT NULL CHECK (octet_length(source_png) BETWEEN 33 AND 4194304),
    rgba32 BYTEA NOT NULL CHECK (octet_length(rgba32)=4096),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (asset_key,revision)
);
CREATE TABLE nation_image_current (
    asset_key TEXT PRIMARY KEY REFERENCES nation_asset,
    revision BIGINT NOT NULL,
    FOREIGN KEY (asset_key,revision) REFERENCES nation_image_version
);
