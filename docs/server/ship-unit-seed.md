# 함종 정의 CSV 로더

작성: Peppone Choi

## 범위와 보존 계약

`ShipUnitSeedLoader.load()`는 `docs/manual/data/ship-units.csv`를 패키지 리소스
`/worldseed/ship-units.csv`에서 읽는다. `read(InputStream)`은 호출자 소유 스트림을
닫지 않으며, 모든 레코드가 유효할 때만 파일 순서의 불변 `ShipUnitSeed.units`를 반환한다.
리소스가 없으면 `Missing worldseed/ship-units.csv`로 실패한다.

행 식별은 `(faction, source.page, sourceRow)`다. `unit`은 고유키가 아니다.
`ManualSource("ship-units.csv", page, record)`의 record는 헤더를 포함한 CSV 레코드 번호로,
페이지 안 행 번호 `sourceRow`와 다르다. 통계 17열의 키는 병합 헤더 원문이고 값은 CSV
디코딩 후 셀 문자열이다. `stats`는 헤더 순서의 방어 복사된 불변 map이다.
`units`도 방어 복사된 불변 list다.

`100/1`, `-`, `5,600`, `0`은 변환·정규화하지 않는다. 인용된 `"5,600"`은 CSV
따옴표만 해제해 `5,600`으로 보존한다. 잘린 이름 `艦）` 2행도 복원·통합·제외하지
않고 `name_clipped` 상태와 함께 보존한다. F6 해소 = 원문 보존이며 원본 PDF 재검증 아님.
`reviewed`는 문서 대조 상태이며 원본 클라이언트 실측·서비스 최종판 검증이 아니다.
출처: [매뉴얼 부록과 서버 시드 사용 제한](../manual/08-organization-units-data.md).

## 검증 규칙 (C1–C10)

실패는 `IllegalArgumentException("ship-units.csv: record N: <사유>")`이고 부분 스냅샷은
반환하지 않는다. 각 레코드에서 아래 순서와 열의 왼쪽부터 검사한다.

1. strict UTF-8 REPORT로 읽고 선두 BOM 1개만 제거한다. 실패는 record 1
   `invalid UTF-8`. 기존 `StrictSeedCsv`를 재사용하며 CSV 문법 오류는 `malformed CSV`다.
2. 헤더 22열은 이름과 순서까지 정확히 일치해야 한다(`unexpected header`).
   데이터는 정확히 22열이어야 한다(`unexpected column count`).
3. 헤더만 있으면 record 1 `empty ship unit seed`, 빈 파일은 `unexpected header`다.
4. 진영은 정확히 `empire` 또는 `alliance`다(`unknown faction`).
5. page는 `[1-9][0-9]*` 및 Int 범위여야 한다(`invalid page`).
   empire 79..89, alliance 90..99 밖은 `page outside verified faction table`이다.
   근거: `tools/manual_tables.py`의 `SHIP_PAGES`와 매뉴얼 부록 p.79–99 함선 표.
6. unit은 공백만 있거나 비어 있으면 안 된다(`missing unit`). 그 외 원문은 유지한다.
7. 모든 통계 열에 같은 전체 일치 문법을 쓴다. `-`, `0|[1-9][0-9]*`,
   `[1-9][0-9]{0,2}(,[0-9]{3})+`, `(0|[1-9][0-9]*)/(0|[1-9][0-9]*)` 중 하나다.
   실패는 `invalid <헤더 원문>`. 숫자 의미·범위·단위를 규정하지 않고 Int 변환도 하지 않는다.
   `0/0`도 문법상 허용하며 열별 형태 제한은 없다.
8. source_row는 `[1-9][0-9]*` 및 Int 범위다(`invalid source_row`).
9. 상태는 정확히 `reviewed` 또는 `name_clipped`다(`unknown verification_status`).
10. 복합키 `(faction, page, source_row)` 중복은 `duplicate source row`다.
    source_row 연속성·page 순서는 입력 제한이 아니다. 같은 이름은 허용한다.

새 의존성·CSV 추상화 없이 `StrictSeedCsv`, `SeedFaction`, `ManualSource`를 재사용한다.
로더의 private rows 중복은 PR50~52 병합 후 공용 helper로 합칠 수 있는 후속 범위다.

## 커밋된 CSV의 관측 사실

원본 CSV SHA-256: `79b9fec6352386cf627c42aa34c03d0eb5f63450f1a431040f6368d4ec01f3b0`.
12091 bytes, BOM 있음, CR 0, LF 119, 마지막 LF, 큰따옴표 528개다.
헤더 22열과 데이터 118행(모두 22열), 고유 헤더 22개, 통계는 17열 × 118 = 2006셀이다.
empire 63행, alliance 55행이며 파일에서 page는 감소하지 않는다.

| 진영 | page별 행수 |
| --- | --- |
| empire | 79:9, 80:9, 81:9, 82:10, 83:4, 84:4, 85:4, 86:4, 87:4, 88:4, 89:2 |
| alliance | 90:9, 91:9, 92:6, 93:9, 94:4, 95:4, 96:4, 97:4, 98:4, 99:2 |

각 (faction, page)의 source_row는 파일 순서로 1부터 연속이며 복합키 중복은 없다.
상태는 reviewed 116, name_clipped 2다. 레코드 83·84는 alliance p.92 source_row 1·2,
이름은 둘 다 `艦）`이고 출력 문자열은 각각 `230`·`300`이다.
같은 진영 안 이름 중복은 이 두 행뿐이다. 진영 간 같은 이름 49개, 전체 고유 이름은 68개다.

통계 셀은 `-` 623개, 쉼표 천단위 문자열 264개, 슬래시 쌍 89개, 그 외 십진 정수다.
빈 셀·앞뒤 공백 셀은 없다. `0`은 레코드 96 alliance p.93 source_row 8의 색적 범위 1셀이다.
쉼표는 색적 범위·최고 속도·물자 탑재량, 슬래시는 건·미사일 열에서만 관측되지만
이는 검증 제약이나 수치 해석이 아니다. p.87·98 장갑의 불균형 값도 보정하지 않는다.
첫 레코드 2는 empire p.79 source_row 1, 마지막 레코드 119는 alliance p.99 source_row 2다.

## 시험과 남은 제약

`ShipUnitSeedTest`는 실제 리소스의 전체 집계·페이지별 행수·순서·대표 셀·출처와,
합성 입력의 문법·검증 오류·오류 레코드 번호·불변성·스트림 소유권을 검사한다.
전체 JVM 빌드·시험은 기존 GitHub-hosted `server.yml`의
`bash ./gradlew build --no-daemon`으로, 컨테이너 무회귀는 기존 `container.yml`로 실행한다.
VPS Gradle/JDK는 NOT_RUN이다. 실제 CI 결과와 exact head SHA는 작업 RESULT.json에 기록한다.

기존 Docker 제약(F1): 이미지 빌드 context가 `server/`이고 현재 `.dockerignore`/복사 경로는
저장소 밖의 `docs/manual/data`를 포함하지 않아 이미지 안에 worldseed CSV가 없다.
app이 이 로더를 호출하지 않아 현재 무증상이며, 컨테이너 CI 성공은 CSV 패키징의 증명이 아니다.
이번 변경에서는 Dockerfile/context/.dockerignore를 수정하지 않는다.

LOGH-28 전체 완료 아님. WorldEngine·app·wire·전투·생산·자동생산·지도·승조원 연결,
수치 의미·단위 변환·잘린 이름 복원은 제외한다. 독립 코드 리뷰와 원본 클라이언트
상호운용 제품 QA는 별도 단계다. `productQA=NOT_RUN`, `receipt=null`.
실제 공식 QA 없이 검증 완료·인수·병합·Linear Done으로 처리하지 않는다.
