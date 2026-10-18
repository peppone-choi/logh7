# 초기 배치 정의 시드

작성자: 최병호

## 범위와 API

`InitialDeploymentSeedLoader.load()`는 `/worldseed/initial-deployment.csv`를 읽는다.
`read(InputStream)`의 스트림은 호출자 소유이며 닫지 않는다. 누락 리소스는
`Missing worldseed/initial-deployment.csv`로 실패한다. `StrictSeedCsv`, `SeedFaction`,
`ManualSource`는 조직 시드 로더와 공유한다. 새 의존성은 없다.

매뉴얼 PDF p.75의 `kind=deployment` 78행을 파일 순서의 불변 스냅샷으로 제공한다.
제국 41·동맹 37, FLEET 24·PATROL 35·GROUND 19이며 표 0..5의 행 수는
12/12/23/12/13/6이다. 각 항목에는 진영·종류·표 번호·부대명·성계·위치 원문과
파일명/페이지/헤더 포함 레코드 번호가 있다. 범위 전개·실제 부대 생성은 하지 않는다.
`automatic_production` 216행(p.76–78)은 구조만 확인하고 반환하지 않는다.
`evidence:manual` (docs/manual/data/initial-deployment.csv, PDF p.75–78)

## 입력 검증 V1–V13

실패는 `IllegalArgumentException`, 메시지는 `initial-deployment.csv: record N: 사유`다.
전체 검사가 끝나야 스냅샷을 반환하므로 뒤쪽의 오류도 부분 결과를 노출하지 않는다.

- V1: UTF-8 오류를 REPORT로 거부하고 선두 BOM 한 개만 제거한다. 토큰화는 `StrictSeedCsv`를 사용한다.
- V2: 헤더는 `page,table,columns,values,faction,kind`와 정확히 같고, 데이터는 한 행 이상이며 모든 행은 6열이다.
- V3: kind는 `deployment`/`automatic_production`만 허용한다.
- V4: faction은 `empire`/`alliance`만 허용한다.
- V5: page/table은 `0|[1-9][0-9]*` 형식과 Int 범위여야 한다.
- V6: 자동 생산 page는 76..78이다. columns/values는 검사·해석하지 않는다.
- V7: 배치 page는 75여야 한다.
- V8: 배치 table은 아래 고정 표의 진영·columns와 정확히 일치해야 한다.
- V9: 배치 values를 `|`로 분할하면 끝 공란을 포함해 정확히 3셀이어야 한다.
- V10: 부대명은 공백만일 수 없다. 성계와 위치는 둘 다 값이 있거나 둘 다 공란이어야 한다. 공란을 이전 행으로 채우지 않는다.
- V11: 셀을 trim하거나 정규화하지 않고 원문을 저장한다.
- V12: (진영, 종류, 부대명)은 중복될 수 없다.
- V13: 배치 행이 0건이면 실패한다.

| table | faction | 종류 | columns 원문 |
| --- | --- | --- | --- |
| 0 | empire | FLEET | 艦隊番号\|星系\|惑星/要塞 |
| 1 | alliance | FLEET | 艦隊番号\|星系\|惑星/要塞 |
| 2 | empire | PATROL | 巡察隊番号\|ユニット配置星系\|ユニット配置惑星 |
| 3 | alliance | PATROL | 巡察隊番号\|ユニット配置星系\|ユニット配置惑星 |
| 4 | alliance | GROUND | 地上部隊番号\|ユニット配置星系\|ユニット配置惑星 |
| 5 | empire | GROUND | 地上部隊番号\|ユニット配置星系\|ユニット配置惑星 |

## 원문 보존과 미검증 사항

- 범위 표기는 46행이다. `第１巡察隊 / ～第３巡察隊` 같은 문자열을 개별 부대로 펼치지 않는다. ` / `는 추출기가 셀 줄바꿈을 치환한 표기다.
- 레코드 48의 제국 PATROL `第53巡察隊 / ～第60巡察隊`는 성계·위치가 모두 빈 문자열이다. 배치 없음/미정이라는 의미를 추정하지 않는다.
- 제국 순찰대에 `第48巡察隊`가 독립 행으로 없고 第47 다음 第49인 것은 관찰 사실, 미검증이다. 원본 PDF를 재확인하지 않았으므로 보정·추가하지 않는다.
- `ボルケーゼ/ボルゲーゼ`, `ロフォーテン/ロフォーデン`, `サンタ・アナ/サンア・アナ`, `シュパーラ/シュバーラ`, `ガイエスブルグ/ガイエスブルク`, `レンテンベルグ/レンテンベルク`, `ヴィレンンシュタイン/ヴィレンシュタイン`, `ルイトボルディング/ルイトポルディング`의 표기 흔들림과 전각/반각 숫자를 그대로 보존한다.
- 함선 수·병력·지휘관은 이 표에 없다. 카드-부대 조인, 지도 ID 연결(LOGH-14), 이름 통일, 자동 생산 해석, WorldEngine/app 연결은 제외한다.

`evidence:manual` (PDF p.75 추출 CSV와 docs/manual/rebuild-verified-tables.py의 deploy(); PDF의 누락 여부 자체는 미검증)

## 기존 Docker 제약과 완료 경계

컨테이너의 빌드 context는 `server/`이며 `.dockerignore`를 포함한 기존 구성에서
저장소 바깥 `docs/` CSV를 가져오지 않는다. 이미지에는 `strategy/`·`worldseed/` CSV가
없고 Gradle의 `from`은 없는 디렉터리를 조용히 무시한다. app이 로더를 호출하지 않아
현재 기동에는 영향이 없다. Dockerfile/context/.dockerignore는 이번에 수정하지 않는다.
후속 F1은 app이 로더를 기동 시 호출하기 전에 결정해야 한다.

이 변경은 LOGH-28 전체 완료 아님. 실제 리소스·합성 입력·불변성 시험은
`InitialDeploymentSeedTest`에 있으며 전체 빌드와 컨테이너 시험은 GitHub-hosted CI에서 수행한다.
VPS Gradle/JDK 시험은 NOT_RUN이다. 코드 CI 성공은 실제 클라이언트 상호운용 판정이 아니다.
`productQA=NOT_RUN`, `receipt=null`. Windows 제품 시험·인수·병합·Linear Done은 수행하지 않는다.
