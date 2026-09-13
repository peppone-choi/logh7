-- USER_APPROVED_2026-09-09 authored command-point economy. The manual supplies
-- only the per-command cost and the deficit-only substitution intent; the grant,
-- regeneration and cap in policies/command-points.json are authored replacements.
-- Existing zero balances are raised once to the approved initial grant so the
-- restored characters can actually pay for commands. No original data is implied.
ALTER TABLE character
    ADD COLUMN points_accrued_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN pcp_spent_direct bigint NOT NULL DEFAULT 0 CHECK (pcp_spent_direct >= 0),
    ADD COLUMN mcp_spent_direct bigint NOT NULL DEFAULT 0 CHECK (mcp_spent_direct >= 0),
    ADD COLUMN pcp_spent_substitute bigint NOT NULL DEFAULT 0 CHECK (pcp_spent_substitute >= 0),
    ADD COLUMN mcp_spent_substitute bigint NOT NULL DEFAULT 0 CHECK (mcp_spent_substitute >= 0);

ALTER TABLE character ALTER COLUMN pcp SET DEFAULT 1600;
ALTER TABLE character ALTER COLUMN mcp SET DEFAULT 1600;
UPDATE character SET pcp = 1600 WHERE pcp = 0;
UPDATE character SET mcp = 1600 WHERE mcp = 0;

-- One row per accepted charge. The fingerprint is the command identity, so a
-- retransmitted request returns the first outcome instead of charging twice.
-- Direct and substituted consumption stay separate because the manual excludes
-- substituted spending from ability-growth accumulation.
CREATE TABLE original_command_point_charge (
    account_id uuid NOT NULL,
    request_fingerprint text NOT NULL,
    character_id bigint NOT NULL,
    pool smallint NOT NULL CHECK (pool IN (0, 1)),
    cost bigint NOT NULL CHECK (cost >= 0),
    direct bigint NOT NULL CHECK (direct >= 0),
    substitute bigint NOT NULL CHECK (substitute >= 0),
    pcp_after bigint NOT NULL CHECK (pcp_after BETWEEN 0 AND 4294967295),
    mcp_after bigint NOT NULL CHECK (mcp_after BETWEEN 0 AND 4294967295),
    authority_version bigint NOT NULL,
    charged_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, request_fingerprint)
);
