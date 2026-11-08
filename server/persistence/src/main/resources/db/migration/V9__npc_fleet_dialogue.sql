CREATE TABLE npc_fleet_dialogue (
    world_identity uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    outfit_id integer NOT NULL CHECK (outfit_id > 0),
    revision bigint NOT NULL CHECK (revision > 0),
    fallback varchar(16) NOT NULL,
    style varchar(80) NOT NULL,
    prompt varchar(500) NOT NULL,
    PRIMARY KEY (world_identity, generation, outfit_id)
);
