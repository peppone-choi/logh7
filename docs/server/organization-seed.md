---
title: 검증된 조직·초기 카드 시드 정의
author: Peppone Choi
created: 2026-10-09
---

# 검증된 조직·초기 카드 시드 정의

LOGH-28의 작은 구현 범위다. `OrganizationSeedLoader.load()`는 기존 `docs/manual/data/org-posts.csv` 121행과 `initial-card-holders.csv` 75행을 엔진 리소스에서 읽어 변경 불가 스냅샷을 반환한다. Gradle `processResources`가 두 원본 CSV만 포함하므로 별도 전사본은 없다. 검증 범위는 [매뉴얼 부록](../manual/08-organization-units-data.md)의 대조 결과와 제한을 따른다. `evidence:manual`

직책은 진영·부서·직책, 초기 카드는 진영·부서·부대·직책 문맥에서 중복을 검사한다. 각 정의는 원본 파일명, PDF 1기준 물리 페이지, 헤더를 포함한 CSV 논리 레코드 번호를 보존한다. `seats`는 원본 `定員数`를 양의 정수로 읽은 값이며 부대당 정원인지 등 적용 단위를 확정하지 않는다. 최소·최대 계급의 `/` 표현과 `-`, 임명권자의 공란, 부대 공란은 그대로 둔다. 카드 보유자 이름에서 계급이나 인물 식별자를 분리하지 않는다. 임명권자는 기존 CSV의 해설 추출값이며 모든 권한을 수기로 확정한 것으로 취급하지 않는다.

잘못된 UTF-8, 헤더·열 수 변경, 잘못된 인용부호, 빈 레코드, 미지원 진영, 해당 진영의 검증된 표 밖 페이지, 빠진 필수 값, 잘못된 정원과 중복 문맥은 로드를 실패시킨다. 정상 CSV의 BOM, CRLF/LF, 인용된 쉼표·큰따옴표·줄바꿈은 지원한다. 실패 시 부분 스냅샷은 반환하지 않는다.

## 원본 불일치의 비의미적 회귀 진단

출처는 [원본 매뉴얼 PDF](https://archive.org/download/gin7manual/gin7manual.pdf), SHA256 `ff9b7b638582febba723413d9956f4166aecbc20746cb35bb4afddcef9515080`의 물리 p.56–67이다. 완료된 원본 대조 작업 `t_48a9acb4`의 `results/t_48a9acb4/SOURCE-REPORT.md`, `RESULT.json`과 `evidence/t_48a9acb4/recount.py`, `recount.json`, `SOURCE-MANIFEST.json`을 재사용했다. 이 경로들은 프로젝트 작업공간의 인계 근거이며 저장소 내 파일 경로는 아니다. 원본 대조 판정은 source mismatch 13 / transcription error 0 / unconfirmed 4로, CSV의 전사 오류를 고치는 작업이 아니다.

`OrganizationSeedTest`의 세 시험은 로더가 원문·출처를 그대로 반환하는지 확인한다. 비교는 `recount.py`와 동일하게 문자열 그대로 수행하며 약칭 치환, 부서 정규화, 임명권자 분할을 하지 않는다.

| 시험 | 원문 비교로 관측한 상세 집합 |
|---|---|
| `rawCardPostMismatchesPreserveAllEightSourceRecords` | `Triple(faction, department, post)`가 조직표에 없는 카드 8행. `initial-card-holders.csv`의 헤더 포함 레코드 22(p.60: 艦隊/司令官副官 キルヒアイス少佐), 37(p.61: イゼルローン要塞/要塞司令官 シュトックハウゼン上級大将), 53·56·58·63·65·68(p.66: 艦隊/副司令官 アーメド·タナンチャイ·モンシャルマン·モートン·チェン·コナリー 각 少将). 전체 정의와 `rawCells`·출처를 고정한다. |
| `rawAppointerNamesPreserveFiveUnresolvedRecordsWithoutSplitting` | 비공란 `appointedBy` 108행 중 `(faction, appointedBy)`가 `(faction, post)`에 없는 5행. `org-posts.csv` 레코드 101(p.63: 同盟軍士官学校/士官学校長 ← 国防委員会・教育部長), 115(p.64: 要塞/要塞司令官 ← 国防委員会・施設部長), 119(p.64: 首都惑星を除く各惑星/惑星守備隊指揮官 ← 国防委員会・防衛部長), 121(p.64: 首都惑星/首都防衛指揮官 ← 国防委員会・防衛部長), 122(p.64: 戦略作戦局/諜報官 ← 国防委員会・情報部長). 5개 전체 조직 정의와 출처를 고정한다. |
| `rawCardCountsAbovePrintedSeatsPreserveFourGroupsWithoutCapacityVerdict` | 동일 raw Triple의 카드 수가 인쇄된 `seats`보다 큰 4종: empire 艦隊/艦隊司令官 9/1(조직 레코드 40, p.57), empire 巡察隊/巡察隊司令 7/1(48, p.58), alliance 艦隊/艦隊司令官 12/1(103, p.63), alliance 巡察隊/巡察隊司令 8/1(111, p.64). 4개 전체 조직 정의와 카드 수, 해당 카드 전부의 원문·출처 및 분리 필드와 원문의 일치를 고정한다. |

집합 전체를 비교하므로 새 불일치, 누락, 원문 자동 보정은 회귀 실패다. 이 진단은 원본 데이터를 잘못된 것으로 거부하는 제품 검증도, 정원/좌석 위반 판정도 아니다. 현재 CSV와 생산 로더·API는 변경하지 않는다.

다음 네 의미 결정은 모두 보류한다.

1. 괄호 약칭 `司令官副官`/`副司令官`과 조직표 `艦隊司令官副官`/`艦隊副司令官`의 동일시.
2. `イゼルローン要塞`와 일반 부서 `要塞`의 인스턴스 관계.
3. `国防委員会・X部長` 임명권자의 자동 분할·결합 규칙.
4. `定員数`의 부대당 해석. 조직표에는 1, 카드표에는 부대별 보유자가 인쇄되어 있지만 적용 단위를 명시한 근거는 없다.

이 정의 공급기는 `WorldEngine`이나 네트워크 메시지에 아직 연결하지 않았다. 초기 카드와 직책 간 연결, 현재 보유권, 임명·파면 정책, 지도 적재·표시, 초기 배치, 자동 생산, 함선·병원 유닛은 후속 범위다. p.92 함선 이름 잘림, 미복원 지형·시설·인구·재고를 추정하지 않는다. LOGH-28 전체 완료를 의미하지 않는다.

Docker 이미지의 빌드 context는 `server/`이므로 저장소 루트의 `docs/manual/data/`는 context 밖에 있다. 현재 이미지에는 이 로더의 `worldseed/`뿐 아니라 기존 `strategy/` CSV도 포함되지 않는다. Gradle `from`은 없는 디렉터리를 조용히 무시한다. 현재 app은 기동 시 두 로더를 호출하지 않으므로 기동에는 영향이 없지만, 향후 연결 전에 별도 작업으로 리소스 포함 방식을 결정해야 한다. 이번 범위에서는 Docker 설정을 변경하지 않는다.

검사 명령은 저장소의 `server/`에서 `bash ./gradlew build --no-daemon`이며 GitHub-hosted `server.yml`에서 실행한다. 원본 CSV 스냅샷과 합성 정상·실패 입력 및 기존 모듈의 회귀를 검사하며 클라이언트 실행은 수반하지 않는다. `container.yml`은 기존 이미지 빌드·기동 계약을 별도로 검사한다. VPS의 Gradle/JDK 빌드는 실행하지 않는다. 실제 실행 결과와 정확한 SHA·CI run URL은 구현 작업의 `RESULT.json`에 기록한다. 제품 QA는 `productQA=NOT_RUN`, `receipt=null`이며 구현·CI 완료는 LOGH-28 전체 완료나 제품 검증·인수·병합 승인이 아니다.
