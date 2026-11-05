-- 국가 정의의 원자 게시와 낙관적 수정 충돌 검사. 작성: Peppone Choi (peppone-choi).
CREATE TABLE nation_definition_revision (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    revision BIGINT NOT NULL CHECK (revision >= 0)
);
INSERT INTO nation_definition_revision (singleton, revision) VALUES (TRUE, 0);
