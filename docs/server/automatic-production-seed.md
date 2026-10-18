# 자동 생산표 원문 보존 시드

`AutomaticProductionSeedLoader.load()`는 `/worldseed/initial-deployment.csv`에서 매뉴얼 p.76–78의 `automatic_production` 행만 읽는다. 원본은 `docs/manual/data/initial-deployment.csv`이며 별도 데이터 사본이나 Gradle 변경은 없다. `read(InputStream)`의 스트림은 호출자가 소유한다. 모든 행을 검증한 뒤에만 스냅샷을 반환하며 부분 결과는 없다.

## API와 보존 범위

`AutomaticProduction`은 `faction`, `table`, `starSystem`, `planet`, `shipUnits`, `crewUnits`, `groundUnits`, `source: ManualSource`를 가진다. `AutomaticProductionSeed.entries`는 CSV 순서의 불변 사본이다. 세 품목 목록도 각각 `Collections.unmodifiableList(ArrayList(...))`로 복사하므로 JVM 변경 가능 목록으로 캐스팅해도 수정할 수 없다. `ManualSource`는 파일명, PDF 페이지, 헤더를 포함한 CSV 기록 번호다.

매뉴얼 정리 문서 `docs/manual/08` §4의 줄바꿈 표기대로 ` / `만 분할한다. 순서와 반복 품목, 같은 거점의 반복 행을 보존한다. 수량·주기·보유량·위치 짝짓기를 해석하지 않으며 함종/병원 정의 조인이나 거점 병합 API는 없다. 성계/행성 이름은 원문이고 지도 ID에는 연결하지 않는다(LOGH-14/F5). WorldEngine, app, wire에는 연결하지 않았다.

## 실제 리소스 고정값

- 216행, CSV 기록 80–295. 제국 132행, 동맹 84행.
- (진영, 페이지, 표) 분포: 제국 (76,0)=44, (77,0)=47, (78,0)=41; 동맹 (76,1)=47, (77,1)=37.
- 모든 `columns`는 `星系名|惑星名|艦艇ユニット種別|乗組員ユニット種別|陸戦兵ユニット種別`, `values`는 5셀이다. 성계/행성 공란은 없으며 성계 58개, (진영,성계,행성) 213개다.
- 같은 거점 반복: 동맹 トリプラ/アガタラ(164,165; 완전 동일), 동맹 バーラト/ハイネセン(170,218), 제국 ビルロスト/ヘイムダル(217,255). 합치지 않는다.
- 공란 셀은 함정 92, 승조원 93, 육전병 1(218). 세 품목 모두 공란인 행은 없다.
- 품목 토큰은 함정 200, 승조원 236, 육전병 439, 합계 875. 기록 104의 함정은 2개, 승조원은 5개이므로 위치별 짝짓기는 불가능하다.
- 기록 80은 제국 アイゼンヘルツ/ヒンターヴィルド, 함정 `[巡航艦Ⅵ]`, 승조원 `[艦隊乗組員]`, 육전병 `[軽装陸戦兵, 軽装陸戦兵, 装甲擲弾兵, 装甲兵]`이다. 기록 218은 함정/승조원 각 4개, 육전병 빈 목록이다.

## 검증 규칙 V1–V11

실패는 `initial-deployment.csv: record N: <reason>`의 `IllegalArgumentException`이다. 공용 `StrictSeedCsv`는 잘못된 인용·빈 기록·독립 CR을 거부한다. 공용 UTF-8/헤더 helper 통합은 PR50–53 병합 후 F7이며 이번에는 기존 로더를 리팩터링하지 않는다.

1. V1: 엄격 UTF-8(`invalid UTF-8`), 선두 BOM 하나 제거, 정확한 헤더(`unexpected header`), 데이터 필요(`empty seed`), 열 6개(`unexpected column count`).
2. V2: kind는 `deployment`/`automatic_production`만(`unknown kind`). deployment의 내용 검증은 PR51 로더 책임이므로 건너뛴다.
3. V3: 자동 생산의 faction은 empire/alliance만(`unknown faction`).
4. V4: page/table은 `0|[1-9][0-9]*` 정수(`invalid nonnegative integer`), Int 오버플로 거부(`integer overflow`). page는 76..78(`page outside verified automatic production table`).
5. V5: 제국 table 0, 동맹 table 1(`table faction mismatch`).
6. V6: columns 원문 정확 일치(`unexpected columns`).
7. V7: values를 `|`로 분할한 5셀(`unexpected value cell count`).
8. V8: 성계/행성 공란 금지(`missing place`).
9. V9: 품목 셀은 빈 문자열이거나 ` / `로 분할한 비어 있지 않은 토큰. 앞뒤 공백이나 잔여 `/` 금지(`invalid item list`). 원문을 trim/교정하지 않는다.
10. V10: 세 품목 모두 공란 금지(`empty production row`).
11. V11: 자동 생산 0건 금지(`empty automatic production seed`, record 1).

## 교차 대조와 F8 원본 판정

시험에서만 동일 진영의 ship-units.unit 및 crew-units.unit와 비교한다. 875토큰 중 870개가 일치하고 다음 5개는 정확히 불일치해야 한다. 로더는 이름 존재 여부를 검사하지 않는다.

출처는 [원본 매뉴얼 PDF](https://archive.org/download/gin7manual/gin7manual.pdf), SHA256 `ff9b7b638582febba723413d9956f4166aecbc20746cb35bb4afddcef9515080`이다. 아래 페이지는 PDF 물리 p.76·77이며 인쇄 쪽번호는 없다. 함종표 p.88(제국)·p.98(동맹)은 `揚陸艦Ⅰ~Ⅳ`만 정의하고, `揚陸艇`은 PDF 전체에서 p.76·77에만 나온다.

F8 원본 이미지 대조 판정은 5건 모두 원본 자체 불일치다(source mismatch 5 / transcription error 0 / unconfirmed 0). CSV는 원본을 정확히 전사했다.

| 기록 | 진영 | 페이지 | 거점 | 원본 함정 셀 | 원본 승조원 셀 |
|---|---|---|---|---|---|
| 83 | 제국 | 76 | アイゼンヘルツ/ゴータ | 揚陸艇Ⅱ | 艦隊乗組員 |
| 109 | 제국 | 76 | ヴァナヘイム/エインヘリヤル | 高速戦艦Ⅴ / 艦隊乗組員 | (공란) |
| 133 | 동맹 | 76 | エルゴン/ブタンディガ | 揚陸艇Ⅳ | 艦隊乗組員 |
| 172 | 제국 | 77 | ヴィッテルスバッハ/ヴィクトアーリエン | 揚陸艇Ⅱ | 艦隊乗組員 |
| 182 | 제국 | 77 | ヴェルラ/シュテファニ | 揚陸艇Ⅱ | 艦隊乗組員 |

기록 109는 원본 인쇄에서 `艦隊乗組員`이 함정 열 `高速戦艦Ⅴ` 아래 둘째 줄에 있고 승조원 열은 공란이다. 이 조판 그대로 함정 `[高速戦艦Ⅴ, 艦隊乗組員]`, 승조원 빈 목록으로 보존한다. `揚陸艇`와 `揚陸艦`의 동일시 및 기록 109의 승조원 재배치는 책임자/소유자 결정 전 하지 않는다. CSV·로더 보정이나 별칭 매핑은 없다.

근거는 완료 작업 `t_6cf2122e`의 `results/t_6cf2122e/SOURCE-REPORT.md`, `results/t_6cf2122e/RESULT.json`, `evidence/t_6cf2122e/`(셀 크롭·전체 페이지·`SOURCE-MANIFEST.json`)에 있다. 이 경로는 `/srv/hermes-workspace/projects/logh/` 아래 프로젝트 작업공간 경로이며 저장소 파일이 아니다. 회귀시험은 불일치 전체 목록의 기록·페이지·진영·열·토큰, 원문 5행의 `AutomaticProduction`·`ManualSource` 전체, 진영별 `揚陸艦Ⅰ~Ⅳ` 정의와 `揚陸艇` 정의 0개를 고정한다.

## 시험 및 남은 경계

`AutomaticProductionSeedTest`는 실제 리소스 고정값(T1), 정확한 5건/870개 교차 대조(T2), 합성 거부(T3), BOM·deployment 혼합·중복·단일 품목 수락(T4), 방어적 복사와 스트림 소유권을 검사한다. 실제 JVM 빌드/시험은 GitHub-hosted `server.yml`의 `:engine:test`, 컨테이너 검사는 동일 head의 `container.yml`로 확인한다. VPS에서는 Gradle/Docker 빌드를 실행하지 않는다.

이 브랜치는 PR53 `logh/t_bb939fa9` 위에 쌓으며 병합 순서는 PR50 → 51 → 52 → 53 → 이 변경이다. PR54는 의존성이 아니다. 현재 Docker 빌드 문맥에는 매뉴얼 CSV가 없으며 Docker 이미지 CSV는 PR54 병합 후에만 포함된다. 컨테이너 CI 통과는 시드 리소스의 이미지 포함이나 원본 클라이언트 상호운용 근거가 아니다.

이것은 마지막 카탈로그 로더 추가이며 LOGH-28 전체 완료가 아니다. 추가 카탈로그 확장을 하지 않고 F5 지도 확보 후 초기 월드 조립으로 진행한다. 독립 리뷰, 실제 제품 QA, 인수, 병합, 배포, Linear Done은 별도 단계다.

productQA=NOT_RUN, receipt=null. Windows 실행 및 실제 원본 클라이언트 제품 시험은 수행하지 않았다.
