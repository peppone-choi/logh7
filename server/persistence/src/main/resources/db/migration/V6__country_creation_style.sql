-- NULL preserves the creation preset inherited by existing national families.
ALTER TABLE nation_family ADD COLUMN creation_style TEXT
    CHECK (creation_style IN ('empire', 'alliance'));
