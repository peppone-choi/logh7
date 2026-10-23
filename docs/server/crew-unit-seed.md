# 병원 유닛 정의 시드

작성: 최병호

## 범위와 출처

`CrewUnitSeedLoader.load()`는 `/worldseed/crew-units.csv`를 읽어 파일 순서의 불변 `CrewUnitSeed.units`를 반환한다. 리소스 누락은 `Missing worldseed/crew-units.csv` 오류다. `read(InputStream)`은 호출자 소유 스트림을 닫지 않으며 전체 검증 성공 전에는 스냅샷을 반환하지 않는다. 목록은 방어 복사하고 JVM 변경 가능 목록 캐스트로도 변경할 수 없다.

입력 정본은 `docs/manual/data/crew-units.csv`이며 바이트를 수정하지 않는다. SHA-256: `9e18090904b27706eaacc79e25b5dc9286e4ec0dfbd46120e29f4837c83dcd5b`.

`ManualSource`는 파일명, PDF 페이지 100, 헤더 포함 CSV 레코드 번호를 기록한다. CSV에 page 열이 없으므로 `tools/manual_tables.py`의 `CREW_PAGE = 100`과 [매뉴얼 요약](../manual/08-organization-units-data.md)의 병원 유닛(p.100) 설명을 근거로 100을 사용한다. `evidence:manual`

## 입력 검증 C1–C8

- C1: UTF-8 오류는 REPORT로 거부하고 선두 BOM 하나만 제거한다. CSV 토큰화는 기존 `StrictSeedCsv`를 재사용한다. 인용·이중 인용·CRLF/LF 규칙을 따르며 잘못된 인용과 단독 CR은 거부한다.
- C2: 헤더는 정확히 `faction,unit,training,ground_attack,ground_defense`, 데이터는 모두 5열이다.
- C3: faction은 `empire` 또는 `alliance`만 허용한다(`unknown faction`).
- C4: unit이 비거나 공백뿐이면 `missing unit`. 나머지 이름은 trim·정규화 없이 원문 보존한다.
- C5: training은 `[1-9][0-9]*` 형식의 Int만 허용한다. 0·음수·선행 0·공란·`-`·오버플로는 `invalid training`이다.
- C6: ground_attack과 ground_defense는 각각 정확한 `-`만 null이다. 나머지는 `0|[1-9][0-9]*` 형식의 Int이며 잘못된 값·오버플로는 `invalid ground_attack` 또는 `invalid ground_defense`다. 두 열 중 하나만 null이어도 허용한다.
- C7: `(faction, unit)` 중복은 `duplicate contextual crew unit`으로 거부한다. 다른 진영의 같은 이름은 허용한다.
- C8: 빈 파일은 헤더 오류, 헤더만 있으면 `empty crew unit seed`로 거부한다.

검증 실패는 `IllegalArgumentException`이며 메시지는 `crew-units.csv: record N: 사유` 형식이다. N은 헤더 포함 레코드 번호다. UTF-8 해독 실패는 레코드 1로 보고한다.

## 보존 사실과 해석하지 않는 것

- 총 11행: 제국 6, 동맹 5. 문맥 중복은 없다.
- 레코드 7·12의 함대 승조원은 ground 두 셀이 `-`다. 이 4개 셀을 0이 아닌 null로 보존한다.
- 근위병·척탄병 교도·장미의 기사는 표에 수치가 있지만 매뉴얼에 당시 생산하지 않는다는 주석이 있다. 수치 존재 ≠ 생산 가능이며 API는 생산 가능 여부를 표현하지 않는다. `evidence:manual`
- training은 매뉴얼의 훈련 과정 값이라는 설명 이상으로 의미·단위를 확정하지 않는다. 계산 규칙에 사용하지 않는다.
- 이 표는 병원 유닛 정의다. 함종의 필요 승조원이나 초기 배치·자동 생산(p.76–78) 표와 조인하지 않는다.

WorldEngine·app·gateway·wire 연결, 육전 계산, 훈련·생산 규칙, 함종 로더, 지도는 범위 밖이다. LOGH-28 전체 완료 아님.

## 기존 Docker 제약

이미지 빌드 context는 `server/`이고 `.dockerignore`를 포함한 현재 구성은 저장소의 `docs/` 데이터를 이미지 빌드에 제공하지 않아 이미지 안에 worldseed CSV가 없다. Gradle `from`은 없는 디렉터리를 조용히 무시한다. app이 로더를 호출하지 않으므로 현재 증상은 없다. 이번 변경은 Dockerfile·context·.dockerignore를 고치지 않는다(F1).

## 시험 구분

`CrewUnitSeedTest`는 실제 패키지의 11행·순서·출처·null과 합성 입력의 허용/거부·스트림 소유·불변성을 검사한다. 전체 서버 빌드와 컨테이너 시험은 GitHub-hosted CI에서 실행한다. VPS Gradle/JDK 실행은 NOT_RUN이다.

서버 단위 시험·CI 성공은 원본 클라이언트 상호운용이나 실제 제품 시험을 대신하지 않는다. `productQA=NOT_RUN`, `receipt=null`. 공식 Windows 시험과 독립 검토 전 검증 완료·인수·병합하지 않는다.
