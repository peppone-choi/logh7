-- 국가 정의·체계·자산·번역의 저장 경계. 작성: Peppone Choi (peppone-choi).
-- 실제 월드 소속/소유권 전환은 월드 액터와 함께 이행한 뒤 활성화한다.
CREATE TABLE nation_calendar (
    calendar_key TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    year_offset INTEGER NOT NULL CHECK (year_offset BETWEEN -10000 AND 10000)
);
CREATE TABLE nation_currency (
    currency_key TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    symbol TEXT NOT NULL,
    minor_digits INTEGER NOT NULL CHECK (minor_digits BETWEEN 0 AND 9)
);
CREATE TABLE nation_asset_pack (
    pack_key TEXT NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    manifest_sha256 TEXT NOT NULL CHECK (length(manifest_sha256) = 64),
    PRIMARY KEY (pack_key, version)
);
CREATE TABLE nation_asset (
    asset_key TEXT PRIMARY KEY,
    pack_key TEXT NOT NULL,
    pack_version INTEGER NOT NULL,
    asset_kind TEXT NOT NULL CHECK (asset_kind IN ('emblem','flag','map_icon','ship_model','ship_texture')),
    relative_path TEXT NOT NULL,
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
    FOREIGN KEY (pack_key, pack_version) REFERENCES nation_asset_pack (pack_key, version)
);
CREATE TABLE nation_ship_style (
    style_key TEXT PRIMARY KEY,
    display_name TEXT NOT NULL
);
CREATE TABLE nation_asset_profile (
    profile_key TEXT PRIMARY KEY,
    native_key TEXT,
    emblem_asset TEXT REFERENCES nation_asset,
    flag_asset TEXT REFERENCES nation_asset,
    map_icon_asset TEXT REFERENCES nation_asset,
    CHECK (native_key IS NOT NULL OR emblem_asset IS NOT NULL OR flag_asset IS NOT NULL OR map_icon_asset IS NOT NULL)
);
CREATE TABLE nation_ship_appearance (
    style_key TEXT NOT NULL REFERENCES nation_ship_style,
    hull_key TEXT NOT NULL,
    model_asset TEXT REFERENCES nation_asset,
    texture_asset TEXT REFERENCES nation_asset,
    native_model_key TEXT,
    CHECK (model_asset IS NOT NULL OR native_model_key IS NOT NULL),
    PRIMARY KEY (style_key, hull_key)
);
CREATE TABLE nation_command_system (system_key TEXT PRIMARY KEY);
CREATE TABLE nation_command (
    system_key TEXT NOT NULL REFERENCES nation_command_system,
    command_key TEXT NOT NULL,
    handler_key TEXT NOT NULL,
    cp_kind TEXT NOT NULL CHECK (cp_kind IN ('NONE','PCP','MCP')),
    cp_cost BIGINT NOT NULL CHECK (cp_cost >= 0),
    wait_seconds BIGINT NOT NULL CHECK (wait_seconds >= 0),
    duration_seconds BIGINT NOT NULL CHECK (duration_seconds >= 0),
    PRIMARY KEY (system_key, command_key)
);
CREATE TABLE nation_command_parameter (
    system_key TEXT NOT NULL,
    command_key TEXT NOT NULL,
    parameter_key TEXT NOT NULL,
    value BIGINT NOT NULL,
    PRIMARY KEY (system_key, command_key, parameter_key),
    FOREIGN KEY (system_key, command_key) REFERENCES nation_command
);
CREATE TABLE nation_rank_system (system_key TEXT PRIMARY KEY);
CREATE TABLE nation_rank (
    system_key TEXT NOT NULL REFERENCES nation_rank_system,
    rank_key TEXT NOT NULL,
    display_name TEXT NOT NULL,
    ladder_key TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    native_rank INTEGER CHECK (native_rank BETWEEN 0 AND 65535),
    PRIMARY KEY (system_key, rank_key),
    UNIQUE (system_key, ladder_key, ordinal)
);
CREATE TABLE nation_office_system (system_key TEXT PRIMARY KEY);
CREATE TABLE nation_office (
    system_key TEXT NOT NULL REFERENCES nation_office_system,
    office_key TEXT NOT NULL,
    display_name TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    authority_key TEXT NOT NULL,
    native_card INTEGER CHECK (native_card BETWEEN 0 AND 65535),
    PRIMARY KEY (system_key, office_key)
);
CREATE TABLE nation_peerage_system (system_key TEXT PRIMARY KEY);
CREATE TABLE nation_peerage (
    system_key TEXT NOT NULL REFERENCES nation_peerage_system,
    peerage_key TEXT NOT NULL,
    display_name TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    PRIMARY KEY (system_key, peerage_key),
    UNIQUE (system_key, ordinal)
);
CREATE TABLE nation_family (
    family_key TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    calendar_key TEXT NOT NULL REFERENCES nation_calendar,
    currency_key TEXT NOT NULL REFERENCES nation_currency,
    command_system TEXT NOT NULL REFERENCES nation_command_system,
    rank_system TEXT NOT NULL REFERENCES nation_rank_system,
    office_system TEXT NOT NULL REFERENCES nation_office_system,
    peerage_system TEXT NOT NULL REFERENCES nation_peerage_system,
    ship_style TEXT NOT NULL REFERENCES nation_ship_style
);
CREATE TABLE nation_country (
    country_id BIGINT PRIMARY KEY CHECK (country_id > 0),
    country_key TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    family_key TEXT NOT NULL REFERENCES nation_family,
    calendar_override TEXT REFERENCES nation_calendar,
    currency_override TEXT REFERENCES nation_currency,
    command_override TEXT REFERENCES nation_command_system,
    rank_override TEXT REFERENCES nation_rank_system,
    office_override TEXT REFERENCES nation_office_system,
    peerage_override TEXT REFERENCES nation_peerage_system,
    ship_style_override TEXT REFERENCES nation_ship_style,
    asset_profile TEXT NOT NULL REFERENCES nation_asset_profile,
    native_power INTEGER NOT NULL CHECK (native_power BETWEEN 0 AND 4),
    native_camp INTEGER NOT NULL CHECK (native_camp BETWEEN 0 AND 1)
);
CREATE TABLE nation_membership (
    character_id BIGINT PRIMARY KEY CHECK (character_id > 0),
    country_id BIGINT REFERENCES nation_country,
    joined_at BIGINT NOT NULL CHECK (joined_at >= 0)
);
CREATE INDEX nation_membership_country ON nation_membership (country_id);
CREATE TABLE nation_territory (
    base_id BIGINT PRIMARY KEY CHECK (base_id > 0),
    country_id BIGINT REFERENCES nation_country,
    controller_character_id BIGINT CHECK (controller_character_id > 0)
);
CREATE TABLE nation_capital (
    country_id BIGINT PRIMARY KEY REFERENCES nation_country,
    base_id BIGINT NOT NULL UNIQUE REFERENCES nation_territory
);
CREATE TABLE nation_money_account (
    owner_kind TEXT NOT NULL CHECK (owner_kind IN ('character','country','base')),
    owner_id BIGINT NOT NULL CHECK (owner_id > 0),
    currency_key TEXT NOT NULL REFERENCES nation_currency,
    balance_minor BIGINT NOT NULL CHECK (balance_minor >= 0),
    PRIMARY KEY (owner_kind, owner_id, currency_key)
);
CREATE TABLE locale_message (
    message_key TEXT PRIMARY KEY,
    context TEXT NOT NULL,
    format_kind TEXT NOT NULL CHECK (format_kind IN ('plain','printf','native')),
    parameter_signature TEXT NOT NULL
);
CREATE TABLE locale_native_binding (
    table_key TEXT NOT NULL,
    group_id INTEGER NOT NULL CHECK (group_id >= 0),
    entry_id INTEGER NOT NULL CHECK (entry_id >= 0),
    source_sha256 TEXT NOT NULL CHECK (length(source_sha256) = 64),
    message_key TEXT NOT NULL REFERENCES locale_message,
    PRIMARY KEY (table_key, group_id, entry_id, source_sha256)
);
CREATE TABLE locale_revision (
    revision_id BIGINT PRIMARY KEY CHECK (revision_id > 0),
    locale_key TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('draft','published','retired')),
    UNIQUE (revision_id, locale_key)
);
CREATE TABLE locale_translation (
    revision_id BIGINT NOT NULL REFERENCES locale_revision,
    message_key TEXT NOT NULL REFERENCES locale_message,
    translated_text TEXT NOT NULL,
    PRIMARY KEY (revision_id, message_key)
);
CREATE TABLE locale_release (
    locale_key TEXT PRIMARY KEY,
    revision_id BIGINT NOT NULL,
    FOREIGN KEY (revision_id, locale_key) REFERENCES locale_revision (revision_id, locale_key)
);
