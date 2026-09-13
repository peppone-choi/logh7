-- Restoration-owned outfit identity/membership, distinct from unit control.
-- No existing player is assigned and no warehouse ACL is converted to membership.
CREATE TABLE original_outfit (
    outfit_id bigint PRIMARY KEY CHECK (outfit_id BETWEEN 1 AND 4294967295),
    power smallint NOT NULL CHECK (power BETWEEN 0 AND 255),
    camp smallint NOT NULL CHECK (camp BETWEEN 0 AND 255),
    kind smallint NOT NULL CHECK (kind BETWEEN 0 AND 255),
    outfit_index smallint NOT NULL CHECK (outfit_index BETWEEN 0 AND 255),
    achievement integer NOT NULL DEFAULT 0 CHECK (achievement BETWEEN 0 AND 65535),
    strategy_id bigint NOT NULL DEFAULT 0 CHECK (strategy_id BETWEEN 0 AND 4294967295),
    -- Ten032B practice bytes. Zero defaults are authored, not recovered initial training.
    practice bytea NOT NULL DEFAULT decode(repeat('00',10),'hex') CHECK (octet_length(practice)=10),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision>0),
    UNIQUE(outfit_id,power,camp)
);

ALTER TABLE character ADD CONSTRAINT original_character_power_identity UNIQUE(character_id,faction);

CREATE TABLE original_outfit_member (
    character_id bigint PRIMARY KEY,
    outfit_id bigint NOT NULL,
    power smallint NOT NULL,
    camp smallint NOT NULL,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision>0),
    FOREIGN KEY(character_id,power) REFERENCES character(character_id,faction)
        DEFERRABLE INITIALLY IMMEDIATE,
    FOREIGN KEY(outfit_id,power,camp) REFERENCES original_outfit(outfit_id,power,camp)
        DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX original_outfit_member_outfit ON original_outfit_member(outfit_id,character_id);
