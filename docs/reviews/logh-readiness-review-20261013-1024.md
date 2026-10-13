# 독립 리뷰: CLOSED readiness 준비물 (a315c69)

taskId: logh-readiness-review-20261013-1024 · 대상 commit `a315c693b86fac2674ad13e08295f8d25529eb39`
(부모 8074503, 원격 main dcf0c578 의 후손. main 대비 LOGH-19 미병합 commit 2개 포함)

## 판정: APPROVE (필수 지적 0)

## 재현 근거 (리뷰어 직접 실행)
- `python3 -m unittest discover -s tools/qa -p 'test_verify_qa_bundle.py' -v` → 8/8 OK
- `node --check tools/qa/second-attach-fixture.js` exit 0, `git diff --check` exit 0, JSON 2개 파싱 OK
- 기존 `resources/WINDOWS-QA-BUNDLE.zip` 을 자기 /tmp 에 복사 후 검증기 실행 → MATCH,
  74469 bytes, SHA256 f68e06a3…5c4c, manifest 16, readiness CLOSED, upload/game/audio/launcher NOT_RUN
- readiness-preparation.json runtime_hashes 7개 = ZIP manifest 해당 항목 hash 일치

## 검토 결과
- 검증기: 추출/import 없음, basename 고정, 전체 ZIP pin + 정확 member 집합 + 중복 + manifest 동등 + 크기/hash. 결과에 CLOSED/NOT_RUN 고정. 적합.
- payload: ia32 + `harmless-second-attach.exe` 이외 즉시 throw, 게임 offset 없음, 원본 함수 호출 없음. 적합.
- 문서: CLOSED·NOT_RUN 유지, F1 44/44·127 과거근거 보존, Steam15324·WINDOWS-BUNDLE.zip 미조작, legacy readiness 무인자 import 금지, upload-resources 금지 명시. 적합.
- REA: 이번 범위는 REA 미사용(open NOT_RUN 명시). LOGH-REA-PORTING 경계와 충돌 없음.

## 권고(비필수)
1. 검증기는 PINNED_BYTES 확인 전에 파일 전체를 read — 크기 stat 선검사가 더 안전.
2. 테스트는 `-s tools/qa` 디렉터리 기준 import 에 의존(`python -I` 로는 import 실패). 문서 명령대로만 실행.
3. 무해 payload 는 실행 driver/exe 부재로 실제 동작 미검증.

## NOT_RUN
실제 게임·audio·Windows attach·새 upload·hosted CI·REA·병합/배포.
