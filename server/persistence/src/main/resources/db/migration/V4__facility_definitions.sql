-- 시설 이름·용도·이용 조건 정의와 거점별 재정의. 작성: Peppone Choi (peppone-choi).
-- 시설 종류(kind)는 원본 constmsg 그룹 73 번호, 시설 ID는 거점 ID×100+종류+1이다. 행이 없는 종류는 서버 기본값을 쓴다.
CREATE TABLE facility_definition_revision (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    revision BIGINT NOT NULL CHECK (revision >= 0)
);
INSERT INTO facility_definition_revision (singleton, revision) VALUES (TRUE, 0);

CREATE TABLE facility_kind (
    kind INTEGER PRIMARY KEY CHECK (kind BETWEEN 0 AND 98),
    display_name TEXT CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 24),
    purpose TEXT NOT NULL CHECK (char_length(purpose) <= 200),
    usage TEXT NOT NULL CHECK (char_length(usage) <= 200)
);

CREATE TABLE facility_override (
    facility_id INTEGER PRIMARY KEY CHECK (facility_id > 100 AND facility_id % 100 BETWEEN 1 AND 99),
    display_name TEXT CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 24),
    purpose TEXT CHECK (purpose IS NULL OR char_length(purpose) BETWEEN 1 AND 200),
    CHECK (display_name IS NOT NULL OR purpose IS NOT NULL)
);
