-- 국가별 RGB 재정의. NULL은 원본 자산색 유지. 작성: Peppone Choi (peppone-choi).
-- 관계(아군/적군) 표시는 국가색과 별개로 유지한다.
CREATE TABLE nation_country_colors (
    country_id BIGINT PRIMARY KEY REFERENCES nation_country,
    revision BIGINT NOT NULL CHECK (revision > 0),
    primary_rgb INTEGER CHECK (primary_rgb BETWEEN 0 AND 16777215),
    secondary_rgb INTEGER CHECK (secondary_rgb BETWEEN 0 AND 16777215),
    map_rgb INTEGER CHECK (map_rgb BETWEEN 0 AND 16777215),
    fleet_rgb INTEGER CHECK (fleet_rgb BETWEEN 0 AND 16777215),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
