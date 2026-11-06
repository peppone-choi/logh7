# 백업·복구 설계

작성: Peppone Choi · 2026-09-27 · LOGH-43

목표는 실제 시간 기준 RPO ≤300초다. RTO는 초기 목표 60분으로 두고 복원 시험에서 측정한다. 현재 Compose는 WAL 외부 아카이브를 구성하지 않았으며 **목표 달성 전**이다. 설계 전체는 `evidence:guess`다.

2026-10-09: 로컬 파일 checkpoint·journal과 전체 세션 복구, 저장 후 입장 응답 경계를 구현했다. [적용 방법·형식·프로세스 강제 종료 검사·미완료 범위](../server/session-persistence.md)를 따른다. 이 결과는 아래 PostgreSQL/WAL/외부 보관 설계와 RPO/RTO 목표의 완료를 의미하지 않는다.

## 세 층의 보관

1. 도메인 스냅샷은 월드 상태와 마지막 적용 이벤트 번호, 스키마 버전, 규칙 버전, RNG 상태를 함께 기록한다. 이벤트는 불변 번호·월드/세션 ID·명령 중복 방지 키·기록 시각을 갖는다. 상태 변경과 이벤트 기록을 같은 DB 트랜잭션에 넣고 commit 이후에만 성공 응답한다. 월드 경계의 일관된 스냅샷은 실 1시간과 점검 전 생성한다. `evidence:guess`
2. PostgreSQL 물리 base backup(예: 주 1회+일일 차등)과 연속 WAL을 **VM 외부** 암호화 저장소에 보관한다. `wal_level=replica`, `archive_mode=on`, `archive_timeout=60s`를 출발점으로 하고 검증된 백업 도구의 archive 명령을 연결한다. pg_dump는 매일 보조 논리 백업으로 남기며 WAL/PITR을 대체하지 않는다. `evidence:guess`
3. 데이터 PD 일일 스냅샷 14일 보관은 OS·볼륨 재구성 보조 수단이다. 실행 중 디스크 스냅샷의 crash consistency와 DB 복구 가능성을 구분하며, DB 파일 복사만으로 정상 백업이라 부르지 않는다. 주간 백업은 다른 장애 영역에도 보관하고 4주 유지한다. `evidence:guess`

## 5분 손실 목표의 조건

`archive_timeout=60s`는 업로드 완료 시간을 보장하지 않는다. 마지막 외부 저장 확인 WAL의 시각/LSN과 현재 commit을 비교하고, WAL 전송 지연·실패·디스크 여유·백업 나이를 감시한다. 정상 목표는 세그먼트 전환 60초+전송 60초 이내, 120초 경고, 240초에 새 변경 명령 접수 중지다. 외부 보관이 복구되기 전에는 성공 응답을 계속 내보내지 않는다. 장애 종류별 RPO는 복원된 마지막 확인 commit의 시각으로 측정한다. `evidence:guess`

단일 VM+비동기 WAL은 극단적인 동시 장애/미감지 장애까지 절대 보장하지 않는다. 300초를 계약상 보장해야 하면 독립 장애 영역의 동기 복제와 쓰기 실패 정책을 재설계한다. 업로드 성공 확인 없이 로컬 WAL을 삭제하지 않으며 보관 기간은 가장 오래된 유효 base backup을 복구할 WAL 연속성을 유지한다. `evidence:guess`

## 복구 절차

1. 새 접속과 쓰기를 차단하고 사고 시각·마지막 승인 명령 ID를 기록한다. 기존 볼륨과 로그를 보존한다. `evidence:guess`
2. 격리된 새 DB 볼륨에 검증된 base backup을 복원하고 WAL을 장애 직전 또는 선택한 PITR 시각까지 재생한다. PostgreSQL 버전·백업 체크섬·WAL 누락을 확인한다. `evidence:guess`
3. 복원 DB의 최신 **완료된** 도메인 스냅샷을 읽어 별도의 인메모리 월드를 구성하고 watermark 이후 이벤트만 순서대로 재생한다. 이미 WAL 복원으로 반영된 DB 잔액·재고에 이벤트를 다시 더하지 않는다. 중복 키와 규칙 버전 불일치는 중단 사유다. `evidence:guess`
4. 계정 수·월드 해시·이벤트 번호 연속성·재화/함선 합계·세션 상태를 비교하고 게임 서버를 읽기 전용 점검 모드로 올린다. 확인 뒤 공인 세션 주소를 유지하여 접속을 재개한다. `evidence:guess`

## 리허설 합격 기준

테스트 데이터에 초 단위 시각과 연속 명령 번호를 넣고 10분 이상 기록한다. 종료 직전 번호를 저장한 뒤 원본 볼륨을 사용하지 않는 격리 복원으로 마지막 복원 commit을 비교한다. 손실 ≤300초, 중복 적용 0, 누락된 WAL 0, 측정한 RTO를 기록한다. 외부 아카이브 전송 차단 시 경고와 쓰기 중지가 동작하는지도 시험한다. 백업 도구·외부 저장소·알림/쓰기 중지 기능은 미구현이며 시험 역시 미실행이다. `evidence:guess`

참고(2026-09-27): [PostgreSQL 연속 아카이빙/PITR](https://www.postgresql.org/docs/18/continuous-archiving.html), [WAL archive_timeout](https://www.postgresql.org/docs/18/runtime-config-wal.html), [Compute 디스크 스냅샷](https://cloud.google.com/compute/docs/disks/snapshots). 배포 전 해당 설정과 선택한 백업 도구의 복원 절차를 함께 검증한다. `evidence:guess`

## 파일 상태 오프라인 백업·복원 도구 (2026-11-03)

현재 게임 서버는 PostgreSQL이 아니라 파일 저장소 세 개(월드 슬롯 `LOGH7_WORLD_STATE_DIR`, 세션 checkpoint·journal `LOGH7_SESSION_STORE_DIR`, 인물 파일 `LOGH7_CHARACTERS_FILE`과 형제 파일 `.*`)를 쓴다. `tools/ops/state_backup.py`가 이 세 저장소를 함께 백업·검증·복원한다.

- `backup`: 서버 운영 API `/health`가 응답하면 거부한다(세 저장소는 실행 사이에만 서로 일관). `*.world-owner` 표지가 지정한 월드 경로와 다르면 거부한다. `*.lock`은 복사하지 않고, 파일마다 크기·SHA-256을 `manifest.json`에 적는다.
- `verify`: 매니페스트의 모든 파일 해시를 다시 확인한다.
- `restore`: 검증 뒤 원래 경로에만 복원한다(world-owner가 절대 경로로 묶여 있어서다). 대상에 상태가 있으면 `--force`가 있어야 하고, 이때 현재 파일을 `<백업>.replaced-<시각>`으로 먼저 옮겨 되돌릴 수 있게 한다.

로컬 리허설(`work/gb-20261103`, 단위 검사 3개 통과): 지상전 8대8 상태를 백업하고 서버를 30초 돌려 전력 1000→369로 바꾼 뒤 강제 종료했다. 실행 중 백업 요청은 거부됐고, `--force` 없는 복원도 거부됐다. 복원 뒤 재시작한 서버는 백업 시점(게임 시각 1331121→1330377, 전력 1000)으로 돌아왔다. 복원+재시작의 RTO는 4.6초였다. 이 결과는 오프라인 백업 한 가지이며, 주기 실행·RPO 감시·실행 중 일관 스냅샷은 미구현이다. `evidence:guess`

외부 보관과 보존 개수(2026-11-03 추가): `export ARCHIVE --dest <다른 디스크·마운트한 원격 공유>`는 검증한 백업을 `.partial` 이름으로 복사한 뒤 대상 매체에서 모든 해시를 다시 읽어 확인하고서야 최종 이름으로 바꾼다. 같은 이름이 있으면 거부한다. `prune ROOT --keep N`은 ROOT 바로 아래 백업 중 매니페스트 생성 시각이 최신인 N개만 남기고, 백업이 아닌 디렉터리는 건드리지 않는다. 확인: 단위 검사 4개 통과, 리허설 백업(69MB)을 다른 드라이브(D:)로 내보내 대상에서 10개 파일 재검증, 백업 세 개 중 최신 두 개 보존. 점검 시간대 절차는 서버 정지 → `backup` → `export` → `prune` → 시작이며, 이를 정해진 시각에 돌리는 작업 스케줄러 등록은 호스트 설정 변경이라 하지 않았다.

## 서버 릴리스 전환·롤백 (2026-11-03)

`tools/ops/server_release.py`는 Gradle `installDist` 결과(`server/app/build/install/app`)를 `RELEASES/<이름>`에 해시 매니페스트와 함께 복사하고, `CURRENT`/`PREVIOUS` 파일로 활성 릴리스를 바꾼다. 실행 중(`/health` 응답)이면 전환을 거부하고, 매니페스트와 다른 파일이 있는 릴리스는 활성화하지 않는다. 시작 스크립트는 `classpath` 출력(`<릴리스>/lib/*`)을 `java -cp`에 넘긴다.

업데이트 절차: 서버 정지 → `state_backup.py backup` → `server_release.py install`·`activate` → 시작·확인. 실패하면 정지 → `state_backup.py restore --force` → `rollback --characters <캐릭터 파일> --world <월드 상태 폴더> --world-definition <월드 정의 파일>` → 시작(아래 저장 형식 복귀 참고).

로컬 리허설(`work/release-20261103`, 단위 검사 2개 통과): 정상 릴리스 `good`로 시작·정지하고 상태를 백업한 뒤, 앱 jar를 잘라 만든 `broken` 릴리스를 활성화하자 서버가 `ClassNotFoundException`으로 시작하지 못했다. `rollback`(good 복귀)과 상태 복원 뒤 재시작해 `/health` 응답과 백업 시점 게임 시각을 확인했다. 무중단 전환·자동 상태 확인·원격 배포는 미구현이다. `evidence:guess`

### 저장 형식이 바뀐 뒤 이전 릴리스로 복귀 (2026-11-06)

PR #290부터 캐릭터 기록은 다음 저장 때 `v2:` 형식으로 바뀐다. 리허설(`work/update-rehearsal-20261106/server`, 워크트리 work 폴더, Git 제외, 원본 클라이언트 실행 없음)은 PR #290 이전 빌드(11-06 0시 `release-lib`)와 현재 main(PR #290·#291 포함) 빌드를 같은 시험 상태 사본에 번갈아 띄웠다.

- 새 릴리스를 20초 실행해도 캐릭터·항행 파일은 다시 쓰이지 않았다(바뀐 파일: 세션 기록·창고 상태·잠금). 이 상태에서는 이전 릴리스도 그대로 적재됐다.
- 캐릭터 5명을 새 릴리스 자체의 인코더로 `v2:`로 바꿔(다음 저장과 같은 값) 두자, 새 릴리스는 정상 적재했다. **이전 릴리스도 오류 없이 시작해 `/health`·월드 적재까지 정상으로 보였다.** 이전 코드는 캐릭터 저장소를 첫 로그인 때 열고, 그때 `v2:` 값을 16진수로 해석하다 실패한다(코드 확인, 로그인 재현은 미검증). 그래서 상태 확인만으로는 형식 불일치를 알 수 없다.
- 업그레이드 전 백업을 `restore-to`로 복원하면 이전 릴리스가 정상 적재했다. 기준 스냅샷과 차이는 리비전·게임 시각 한 틱뿐이었다.

그래서 `server_release.py activate`·`rollback`에 `--characters <LOGH7_CHARACTERS_FILE>`를 더했다. 캐릭터 기록에 대상 릴리스가 읽지 못하는 형식이 있으면 전환을 거부하고 업그레이드 전 백업 복원을 안내한다. 기본 파일과 이후 세션 세대 파일(`<파일>.session-<n>`)을 모두 본다. 판정은 대상 릴리스 jar에 그 형식의 해석 클래스(`org/logh7/gateway/CharacterProfile.class`)가 있는지로 한다. 실제 두 릴리스에서 `v2:` 상태의 이전 릴리스 복귀는 거부, 업그레이드 전 캐릭터로는 허용, 새 릴리스 전환은 허용됨을 확인했다(검사 3개 통과). 업데이트 절차의 복귀는 따라서 **정지 → `state_backup.py restore --force`(업그레이드 전 백업) → `rollback --characters …` → 시작** 순서다. 캐릭터 외 저장 파일은 아래 `--world`가 본다.

`--world <LOGH7_WORLD_STATE_DIR>`(같은 날, PR #294 형식42 반영): 창고·항행·월드 저장 파일의 머리말(`LOGH7-STOCK-N`, `LOGH7-NAV-N`, `LOGH7-WORLD-N`)은 그동안 여러 번 올라갔고(창고 42판까지), 이전 릴리스는 모르는 머리말이면 시작하지 못한다. 그래서 대상 릴리스의 해당 저장소 클래스(`FilePlanetStockStore`·`FileNavigationStore`·`FileWorldStateStore`)에 그 머리말이 **정확한 문자열 상수**로 있는지 본다(`LOGH7-STOCK-4`처럼 앞부분만 같은 상수는 인정하지 않는다). 월드 폴더는 `CURRENT`가 가리키는 슬롯만 본다(실패한 업데이트가 비활성 슬롯에 남긴 새 형식은 다음 저장이 덮어쓰므로 막지 않는다). `--characters`를 주면 옛 단독 파일(`.navigation`·`.planet-stocks`)도 본다. 실제 세 릴리스(PR #290 이전·PR #291 이후·PR #294 main `931e2b81`)로 확인: 실제 시험 상태(STOCK-38·NAV-14)는 셋 다 허용, 창고 머리말을 `LOGH7-STOCK-42`로 둔 사본은 main만 허용하고 두 이전 릴리스는 거부했다. 거부가 맞는지 이전 릴리스를 그 상태로 띄우자 `Invalid planet stock header`로 시작에 실패했다(캐릭터 `v2:`와 달리 바로 드러난다). 검사 4개 통과. 세션 기록·계정·평판 파일의 형식 판정은 아직 없다.
### 국가 정의 DB와 릴리스 복귀 (2026-11-06)

국가 정의 편집(Codex `country-definition-editor`, 새 `PostgresCountryDefinitions`·`V3__country_definition_revision.sql`) 이후 `LOGH7_DEFINITIONS_JDBC_URL`이 설정된 배포에서는 국가·계열·역법 정의의 원천이 PostgreSQL이다. 이 정의는 `state_backup.py`의 파일 백업에 들어가지 않는다.

- 업데이트 전: 파일 백업과 함께 정의 DB를 따로 백업한다(예: 정의 테이블 `pg_dump --format=custom`, 또는 위 2번의 물리 백업 시점 기록). 두 백업의 시각을 같이 남긴다.
- 복귀: `server_release.py activate`·`rollback --definitions-db`는 대상 릴리스에 `org/logh7/persistence/PostgresCountryDefinitions.class`가 없을 때 전환을 거부한다. 같은 `LOGH7_DEFINITIONS_JDBC_URL`이 기존 국가 색상 DB(`PostgresCountryColors`)에도 쓰이므로 환경 변수만으로는 켜지 않는다. 전체 정의를 DB에 게시한 뒤부터 이 플래그를 준다. 이 리더가 없는 릴리스는 DB에 게시된 정의를 무시하고 내장 목록으로 시작하므로, 오류 없이 다른 국가 정의로 돌게 된다(Codex 설명, 이 리허설에서 기동 재현은 하지 않음).
- 리더가 없는 릴리스로 꼭 돌아가야 하면 정의 DB 사용을 끄고(`LOGH7_DEFINITIONS_JDBC_URL` 제거) 그 릴리스 시점의 파일 상태와 정의로 복원한다.

확인: 검사 5개 통과. 실제 릴리스 두 개(PR #290 이전, PR #294 main `931e2b81`)는 둘 다 리더가 없어 `--definitions-db`에서 거부됨을 확인했다(PR #294까지 이 클래스가 없었다). 이전 릴리스를 정의 DB 배포에서 띄우는 시험은 미검증이다.

정의 DB 복원 리허설(같은 날, PR #297 머지 `27eb56ec` 뒤, `work/update-rehearsal-20261106/definitions`, Git 제외): Codex QA의 `pg_dump --format=custom` 덤프(PostgreSQL 18.6, 표 29개, 국가 2·3·42, `nation_definition_revision` 7)를 새 볼륨 없이 tmpfs로 띄운 전용 QA 컨테이너(`postgres:18`, 127.0.0.1 전용, 시험용 비밀번호)에 `pg_restore --no-owner --exit-on-error`로 복원했다. 복원 DB를 다시 덤프해 또 다른 DB에 복원하자 29개 표의 건수와 내용 해시가 모두 같았다. main 릴리스는 복원한 DB를 정의 원천으로 시작해 월드를 적재했다(이 릴리스는 정의 DB가 유효하지 않으면 시작을 거부한다). 같은 상태에서 `--definitions-db` 판정은 main은 허용, PR #294 릴리스는 거부했다. 컨테이너는 정리했고 기존 Docker 볼륨 163개는 그대로다. 운영 DB의 정기 백업 연결과 리더가 없는 이전 릴리스를 정의 DB 배포에서 띄우는 시험은 미검증이다. 복원 명령 예: `createdb <새 DB>` → `pg_restore --no-owner --exit-on-error -d <새 DB> definitions.dump` → 표 건수 확인 → `LOGH7_DEFINITIONS_JDBC_URL`을 새 DB로 바꿔 시작.
### 월드 정의 schema와 릴리스 전환 (2026-11-06)

국가별 시작 위치(`start.country.<id>`, Codex `live-country-identity`)가 들어간 월드 정의 파일(`LOGH7_WORLD_FILE`)은 `schema=2`다. 지금까지의 릴리스는 `schema=1`만 받아 시작을 거부한다. `server_release.py activate`·`rollback --world-definition <LOGH7_WORLD_FILE>`은 `schema`가 1이 아니면 대상 릴리스의 `org/logh7/engine/Galaxy$Companion.class`에 `Country starts require world schema 2`가 정확한 상수로 있어야 전환한다. 표에 없는 schema는 거부한다. 확인: 검사 6개 통과. 실제 릴리스(main `1c94aecf` 이전 빌드 `27eb56ec`)는 schema 1 정의는 허용, schema 2로 바꾼 사본은 거부했다. PR #302(main `bad26c84`) 머지 뒤 실제 릴리스로 다시 확인: `start.country.2`를 넣은 schema 2 사본은 `bad26c84` 릴리스에서 허용되고 같은 상태로 시작해 월드를 적재했다. 리더가 없는 `27eb56ec` 릴리스는 판정에서 거부됐고, 실제로 띄우자 `IllegalArgumentException: Failed requirement`로 시작에 실패했다(판정이 맞음).
### 창고 STOCK-43 판정 실측 (2026-11-06)

PR #307(main `b4625ee5`)이 국가별 직위·수도를 담는 창고 형식 `LOGH7-STOCK-43`을 더했다. 판정 도구는 머리말 일반 규칙이라 바꾸지 않았다. Codex QA 세계(`work/country-office-holdings-20261106`, 국가 2의 수도 1049·직위 보유)를 `state_backup.py backup`(원본 읽기 전용)→`restore-to`로 QA 사본에 옮겨 확인했다.

- `--world` 판정: `b4625ee5` 허용, 그 직전 main `66d52986` 거부(`LOGH7-STOCK-43 in stocks.tsv`).
- 실제 기동: `b4625ee5`는 적재하고 `capitals={CountryId(value=2)=1049}`를 보였다. `66d52986`은 `Invalid planet stock header`로 시작에 실패했다. 같은 `66d52986` 빌드는 STOCK-38 상태에서는 정상 적재해, 실패 원인이 형식임을 대조했다.
- 처음 빌드한 `66d52986`은 소스를 일부만 풀어 `l10n` 리소스가 빠져 다른 오류로 실패했다. 이전 릴리스를 소스에서 다시 만들 때는 `server`와 함께 `config/countries`·`docs/manual/data`·`l10n`·`tools/l10n`·`docs/IMPLEMENTATION-CHECKLIST.md`가 필요하다. 운영에서는 `server_release.py install`로 보관한 릴리스를 쓰므로 해당하지 않는다.
### 업데이트 전체 흐름 리허설 (2026-11-06)

위 도구를 한 흐름으로 이어 실제 두 릴리스로 돌렸다(`work/update-rehearsal-20261106/full/rehearse_update.py`, 워크트리 work 폴더, Git 제외, QA 상태 사본, 원본 클라이언트 실행 없음). 이전 릴리스는 main `f4dbe4ef`(유지보수 기능 포함, 창고 STOCK-42 리더 없음), 새 릴리스는 main `bad26c84`(PR #302)다.

| 단계 | 결과 |
|---|---|
| `activate`(형식 검사 `--characters --world --world-definition`) → 이전 릴리스 시작 | 허용, 2.1초에 월드 적재 |
| `POST /commands/maintenance/on` → `POST /commands/notice` | 둘 다 `changed`, `logh7_maintenance_closed 1` |
| `POST /commands/backup` → 정지 → `state_backup.py verify` | 실행 중 백업 11개 파일 확인 |
| 새 릴리스 `activate`(형식 검사) → 시작 | 허용, 1.8초에 적재. 차단은 메모리 상태라 재시작 뒤 `0`([유지보수](maintenance.md) 6단계와 같음) |
| 15초 실행 뒤 정지 → 형식 검사 `rollback` | 허용. 새 릴리스가 그동안 이전 릴리스가 못 읽는 형식을 쓰지 않았기 때문이다 |
| 이전 릴리스 다시 시작 | 1.8초에 적재 |

새 릴리스가 새 형식을 쓴 경우의 거부 → 업그레이드 전 백업 복원 → 복귀 경로는 위 저장 형식 절(캐릭터 `v2:`, 창고 STOCK-42)에서 따로 확인했다. 실제 플레이어 접속 중 전환, 세션 기록 안의 전술 명령 상태 버전(바이너리 기록 내부 1~4판)의 판정은 이 리허설에 없다.
## 운영 지표 `/metrics` (2026-11-03)

운영 API(`127.0.0.1`, 기본 47901·시험 47951)에 Prometheus 텍스트 형식 `/metrics`를 더했다. 엔진 정상 여부(`logh7_engine_up`), 월드 리비전·명령 접수/거부 누계, 세션 세대·게임 시각·참가/접속 수·종료 여부, 전술 인스턴스 수, 진행 중 지상전 거점 수 11개다(검사 `metricsExposeHealthAndCountersInPrometheusText`). 실제 시험 서버에서 `engine_up 1`, 참가 6·접속 0, 지상전 1을 확인했다. 수집기 연결(2026-11-03 추가): 운영 API 바인드 주소를 `LOGH7_OPS_BIND`로 바꿀 수 있게 했고, 루프백이 아닌 주소는 토큰(`LOGH7_OPS_TOKEN` 또는 비밀 파일 `LOGH7_OPS_TOKEN_FILE`)이 없으면 서버 시작을 거부한다(실제 서버에서 `0.0.0.0` 무토큰 시작 거부 확인). 선택 덮어쓰기 `deploy/docker-compose.metrics.yml`이 게임 서버를 백엔드 망에 토큰과 함께 열고, Prometheus가 `prometheus-game.yml`의 Bearer 설정으로 `game-server:47901/metrics`를 긁는다. 기본 Compose와 CI 계약(게임 서비스 비밀 없음·루프백 수신)은 그대로다. `docker compose config`로 병합 결과를 확인했으나, Linux Docker에서 실제 수집은 실행하지 않았다. 지연·송신 큐·메모리 지표는 미구현이다. `evidence:guess`

## 읽기 전용 관리 화면 `/admin` (2026-11-03)

운영 API에 HTML 상태 화면 `/admin`을 더했다(루프백 전용, 10초 자동 새로 고침). 엔진 상태·리비전·명령 접수/거부·세션 세대/게임 시각/참가·접속/종료·전술 인스턴스·진행 중 지상전 거점과, 인물별 계정·인물 ID·세력·그리드·거점·전략/전술·PCP/MCP 표를 보인다. 값은 HTML 이스케이프한다. 명령 버튼은 두지 않았다. 운영 명령은 감사 기록과 운영자 헤더가 필요한 POST 경로로만 보낸다. 시험 서버에서 브라우저로 표시를 확인했다. 계정 관리·세션 재시작 같은 조작 화면과 인증은 미구현이다. `evidence:guess`

## 운영 API 토큰과 관리 화면 조작 (2026-11-03)

`LOGH7_OPS_TOKEN`(16자 이상)을 주면 `/health`를 뺀 모든 운영 경로가 `Authorization: Bearer <토큰>` 또는 HTTP Basic(사용자명 무관, 비밀번호=토큰)을 요구한다. 없거나 틀리면 401과 `WWW-Authenticate: Basic`을 돌려주므로 브라우저가 로그인 창을 띄운다. 비교는 상수 시간이다. 토큰이 없으면 이전처럼 루프백 바인딩만으로 보호한다. Prometheus는 `basic_auth`나 `authorization` 설정으로 `/metrics`를 긁는다.

`/admin`에 「엔진 응답 확인(probe)」「세션 재시작(종료된 세션만)」 버튼을 더했다. 각 버튼은 운영자 이름 입력과 함께 `/admin/probe`·`/admin/restart`로 POST하며, 기존 헤더 경로와 같은 감사 기록(요청·결과 두 줄, 운영자·원격 주소)을 남긴다. 다른 출처의 `Origin`이 붙은 POST는 403으로 거부한다. 감사 기록이 설정되지 않으면 조작은 503으로 거부된다(기존 규칙). 입력이 지워지지 않도록 자동 새로 고침은 뺐다.

확인: 운영 API 검사 23개 통과(토큰·Basic·Bearer·폼 감사·교차 출처 거부 포함). 실제 서버(`LOGH7_OPS_TOKEN`·`LOGH7_OPS_AUDIT_FILE` 설정)에서 `/health` 200, 토큰 없는 `/admin` 401, Basic 인증 200, 폼 probe `queued`와 감사 두 줄, 교차 출처 403을 확인했다. 계정 관리 화면과 운영자별 개별 자격 증명은 미구현이다. `evidence:guess`

## 계정 관리 (2026-11-03)

저장 방식은 기존 계정 파일을 그대로 쓰는 쪽을 기본값으로 골랐다(PostgreSQL 계정은 배포 설계와 함께 다시 정한다). `FileAccounts`가 `LOGH7_ACCOUNTS_FILE`을 실시간 맵으로 감싸, 로그인할 때마다 파일 수정 시각을 보고 다시 읽는다. 쓰기는 옆의 임시 파일에 쓴 뒤 원자적으로 바꾼다. 계정 이름은 `[A-Za-z0-9._-]` 1~30자, 비밀번호는 출력 가능한 ASCII 1~10자다(원본 로그인 문자열 길이). `/admin`의 「계정」 절은 이름만 보이고(비밀번호는 표시하지 않음), 추가·비밀번호 변경·삭제 폼을 둔다. 각 조작은 감사 기록(`ACCOUNT_SET`/`ACCOUNT_REMOVE`, 결과 `CHANGED`/`INVALID`/`NOT_FOUND`)에 남는다.

확인: 운영 API 검사 16개, `FileAccountsTest` 2개 통과. 계정 파일 사본(`work/accounts-20261103`)으로 서버를 띄우고 폼으로 `qa1`을 추가하자 화면에 7개 계정이 보였고, 감사 기록에 `ACCOUNT_SET`·`CHANGED`가 남았다. 서버를 재시작하지 않은 채 원본 클라이언트가 `qa1`로 로그인해 로비에 들어갔다. 인물이 없는 계정의 「게임 시작」은 기존 계정 multi2와 똑같이 「세션 서버 오류로 현재 캐릭터를 표시할 수 없습니다」를 보여, 계정 편집과는 무관한 기존 동작이다. 운영자별 자격 증명은 남는다. `evidence:guess`

비밀번호 해시 저장(같은 날 추가): 관리 화면으로 추가·변경한 비밀번호는 `pbkdf2-sha256:<반복>:<솔트>:<해시>`(PBKDF2-HMAC-SHA256, 반복 600,000회, 16바이트 솔트, `AccountPassword`)로 저장하고, 로그인은 상수 시간 비교로 확인한다. 손으로 만든 시험 파일의 평문 항목은 그대로 동작한다. 게이트웨이 전체 검사를 통과했고, 실제 서버에서 `qa2`를 추가하자 파일에 해시만 남았다. 원본 클라이언트는 맞는 비밀번호로 로비에 들어갔고, 틀린 비밀번호에는 「실패」 대화상자를 보였다. 원본 클라이언트는 비밀번호를 평문으로 보내므로(암호화된 접속 안), 해시는 저장 파일 유출만 막는다.

## 운영자별 로그인 (2026-11-03)

`LOGH7_OPS_USERS_FILE`(이름=`pbkdf2-sha256:<반복>:<솔트>:<해시>` properties)을 주면 `/health`를 뺀 운영 경로가 HTTP Basic 운영자 로그인을 받는다. 로그인한 이름이 감사 기록의 운영자가 되며, 헤더·폼에 적은 이름보다 우선한다. 공유 토큰(`LOGH7_OPS_TOKEN`)은 Prometheus 같은 기계용으로 그대로 쓸 수 있다. 이때 운영자는 이전처럼 스스로 적은 값이다. 비루프백 바인딩은 토큰이나 운영자 파일 중 하나가 있어야 한다. 항목은 `tools/ops/ops_user.py <파일> <이름>`이 비밀번호를 두 번 입력받아(화면에 보이지 않음, 12자 이상) 추가·교체한다. 파일은 바뀌면 다시 읽는다.

확인: 운영 API 검사 17개 통과. 그중 하나는 Python 도구가 만든 해시를 Kotlin이 검증하는지 본다. `ops_user.py` 검사 1개도 통과했다. 실제 서버에서 로그인 없음·틀린 비밀번호는 401, 맞는 로그인은 200을 받았다. 폼에 `operator=someone`을 적어 보낸 probe가 감사 기록에는 로그인한 `peppone`으로 남았다. `evidence:guess`

## 실행 중 백업과 주기 실행 (2026-11-04)

`LOGH7_BACKUP_DIR`을 주면 서버가 멈추지 않고 백업한다. 복사는 월드 액터의 `Quiesce` 명령으로 커밋 사이에서만 일어난다. 모든 저장소는 원자적 교체나 액터만 쓰는 저널 덧붙이기로 기록하므로, 이 복사본은 커밋 사이에서 강제 종료한 상태와 같다. 해시와 매니페스트는 액터가 다시 돌기 시작한 뒤 계산한다. 다 끝나야 `.partial`을 `state-YYYYMMDD-HHMMSS`로 이름 바꾼다. 형식은 `state_backup.py`의 `LOGH7-STATE-BACKUP-1`이고 `"live": true`가 붙는다. 그래서 `verify`·`restore`·`export`·`prune`을 그대로 쓴다.

- 주기: `LOGH7_BACKUP_INTERVAL_MINUTES`(0 또는 없음 = 수동만). 보존 개수는 `LOGH7_BACKUP_KEEP`(기본 48)이다. 오래된 실행 중 백업은 서버가 지운다.
- 수동: `/admin`의 「지금 백업」 버튼 또는 `POST /commands/backup`(감사 `BACKUP` 요청·결과 두 줄).
- 감시: `/metrics`의 `logh7_backup_last_success_seconds`(마지막 성공 시각, 처음엔 0). `time() - 이 값`이 주기의 2배를 넘으면 경보를 낸다(RPO 감시). `/admin`에도 마지막 백업 시각을 보인다.

로컬 리허설(`work/gb-20261103`, 세션 저장소 77MB). 서버를 주기 1분·보존 3으로 띄웠다. 1분 뒤 주기 백업이, 이어서 수동 백업이 생겼다. 엔진이 멈춘 시간은 각각 61ms와 34ms였다. 수동 백업 직후 게임 시각은 1332135였다. 30초 더 돌려 1333173이 된 뒤 강제 종료했다. 그 수동 백업을 `state_backup.py verify`(10개 파일)로 확인하고 `restore --force`로 복원해 다시 시작했다. 엔진은 정상이었고, 시작 약 10초 뒤 게임 시각은 1332447이었다. 백업 시점에서 이어진 것이다. 감사 기록에는 `BACKUP` 요청과 `CHANGED`가 남았다. `evidence:guess`

자동 외부 보관(같은 날): `LOGH7_BACKUP_EXPORT_DIR`(다른 디스크·마운트한 공유)를 주면, 백업마다 그곳에 복사한다. 복사한 파일을 다시 읽어 원본과 해시를 대조한 뒤에야 완성 이름을 붙인다. 보존 개수도 같게 정리한다. 실서버에서 수동 백업 하나(엔진 정지 62ms)가 D: 드라이브로 내보내졌고, `state_backup.py verify`가 그 사본의 10개 파일을 확인했다. 무중단 서버 전환은 남음.

### 정의 DB 정기 백업 (2026-11-06)

`LOGH7_BACKUP_DIR`과 `LOGH7_DEFINITIONS_JDBC_URL`이 함께 있으면, 실행 중 백업(수동·주기·외부 내보내기)마다 정의 DB 전체를 같은 `state-*` 디렉터리의 `definitions/definitions.sql`로 남긴다. 따로 도는 서비스나 스케줄러는 없다.

- **한 시점**: 국가(V1~V3)와 시설(V4)을 포함한 현재 스키마의 모든 표를 REPEATABLE READ 읽기 전용 트랜잭션 하나에서 `COPY`한다. 국가와 시설 행이 같은 순간의 값이다. 파일은 매니페스트에 해시로 들어가 `verify`·`export`·`prune`이 그대로 다룬다.
- **월드와 짝**: 월드 파일 복사(엔진 일시 정지) 직후, 엔진이 다시 돈 다음에 정의 DB를 읽는다. 그래서 DB가 느리거나 멈춰도 게임이 멈추지 않는다. 둘은 같은 백업 이름으로 묶이고, 시차는 그 사이 몇 밀리초~초다. 두 저장소는 서로를 참조하지 않으므로 이 시차로 어긋나는 값은 없다.
- **장애 시**: 정의 DB를 읽지 못하면 그 백업 전체를 버리고(`.partial` 삭제) 게시하지 않는다. `logh7_backup_failures_total`이 올라가고 기존 백업 실패·지연 경보가 잡는다.
- **복원 경계**: `state_backup.py restore`/`restore-to`는 파일만 되돌리고, 정의 DB 복원 명령을 알려 준다. 서버를 멈춘 뒤 V1~Vn 스키마가 있는 DB에 `psql -v ON_ERROR_STOP=1 -1 -f <백업>/definitions/definitions.sql`을 실행한다. 이 스크립트는 모든 표를 비운 뒤 참조되는 표부터 넣고 시퀀스를 맞추며, 한 트랜잭션이라 중간 실패 시 아무것도 바뀌지 않는다.

확인(`work/update-rehearsal-20261106/definitions-backup`, 워크트리 work 폴더, Git 제외, 원본 클라이언트 실행 없음):
- 검사: `LiveStateBackupTest` 4개(정의 DB 장애 시 아무것도 남지 않음, 국가·시설 행을 담은 백업을 다른 스키마에 재생하면 모든 표가 같음·외부 내보내기 포함), `test_state_backup.py` 8개(정의 묶음이 있는 백업의 검증·파일 복원·`restore-to`·변조 거부), persistence 209·app 10·도구 22 통과.
- 실서버: 전용 QA PostgreSQL(tmpfs, 새 볼륨 없음)에 국가 정의와 시설 편집을 넣고 `LOGH7_BACKUP_EXPORT_DIR`까지 켠 서버로 `POST /commands/backup`. 백업과 내보낸 사본 모두 `verify` 12개 파일. 그 `definitions.sql`을 V1~V4만 있는 새 DB에 `psql -1`로 복원하자 32개 표의 건수·내용 해시가 원본과 같았다(국가 3·시설 종류 1·재정의 1 포함). DB 컨테이너를 일시 정지한 동안의 백업은 500으로 실패했고 새 백업·`.partial`이 생기지 않았으며 실패 지표가 0→1이 됐다. DB를 되살린 뒤의 백업과 외부 내보내기는 정상이었다. 컨테이너는 지웠고 Docker 볼륨 163개는 그대로다.
- 미확인: 실제 운영 DB와 운영 주기에서의 실행.
## 세계 편집과 작업 현황 (2026-11-05)

`/admin/world`는 거점의 군수물자·식량·광물·금고와 전체 인구·성인 인구·민심·치안을 편집한다. 인구는 명 단위 정수, 민심·치안은 0~100% 정수이며 성인 인구는 전체 인구를 넘을 수 없다. 인구 정보가 없는 거점을 0명으로 만들어 표시하지 않는다. 기존 자원 네 항목만 보내는 요청은 인구 정보를 보존한다.

기존 운영자 인증과 Origin 검사·감사 기록을 사용하며, 거점의 버전이 바뀌었으면 409로 거절한다. 다른 운영자의 수정이나 생산·인구 변화가 있으면 최신 값을 불러와 다시 편집한다. 실시간 현재 값 조회는 편집 중인 입력을 덮어쓰지 않는다. 저장은 월드 액터에서 항해 상태와 재고를 함께 커밋한 뒤 공개한다. 저장소 장애 때는 자원·인구 변경 모두 공개하지 않고 추가 편집을 503으로 거절한다. 항성/행성 정의·소유권·NPC·이미지·국가 색상과 되돌리기는 이 화면의 지원 범위 밖이다.

실제 반영 확인(2026-11-06, 작성: Peppone Choi ([peppone-choi](https://github.com/peppone-choi))): `work/world-editor-native-20261106`의 복원한 시험 저장에서 headless Chrome의 실제 폼 제출로 자원·금고와 인구·민심·치안을 저장했다(HTTP 200·브라우저 오류 없음). 원본 클라이언트의 개요·환경/내정·창고·예산 창에서 군수물자 123456·식량 234567·광물 345678·거점 금고 765432·인구 123456789명·성인 74074073명·민심 73·치안 81을 확인했다. 서버 재시작 뒤 run-04 재접속에서도 같은 8개 값이 유지됐다. 국가 2/3/42의 금고·완료 예산 견적·거점 소유국·인물 위치는 저장 모델로 대조했고, 원본 예산 창에서도 국고 450000·이전 완료 이체 50000이 보존됐다. 서버·원본 모두 정리했고, 네 원본 실행은 무음 장치·Job 0·원본 해시 보존을 확인했다. 입력 백업 10개 파일도 다시 검증했다. 엔진 5개·실제 HTTP/액터 검사 1개가 통과했다. 운영 중인 주 서버의 상태를 편집한 결과는 아니다.

`/admin/progress`는 `IMPLEMENTATION-CHECKLIST.md`의 완료/전체 체크 수를 읽는 현황판이다. 같은 인증을 사용한다. 서버 작업 폴더나 상위 폴더의 `docs/IMPLEMENTATION-CHECKLIST.md`를 찾으며, 다른 배치에서는 `LOGH7_CHECKLIST_FILE`로 읽기 전용 파일을 지정한다. 파일이 없거나 잘못되면 데이터 API는 503이고 화면은 마지막 확인 값을 유지한다. 배포 이미지에 문서를 자동으로 포함하지 않으므로 컨테이너에서는 이 파일을 명시적으로 마운트한다.

게임 서버를 건드리지 않고 로컬 작업 현황판만 실행하려면 저장소의 `server` 폴더에서 `gradle :ops-api:runProgress`를 실행한다. 주소는 `http://127.0.0.1:48093/`이며 게임 엔진·계정 저장소·`.env`를 로드하지 않는다. 약 5px 선의 도넛 한 개가 전체 또는 선택한 범위의 **체크 항목 이행률**을 표시한다. 게임 완성도나 온라인 공개 승인을 뜻하지 않는다. 부분 구현·실기 미확인은 미완료로 남고, 범위가 추가되면 분모가 달라진다.

표시 확인(2026-11-05, 작성: Peppone Choi ([peppone-choi](https://github.com/peppone-choi))):
`work/progress-browser-20261105/run-03`에서 별도 비활성 데스크톱의 headless Chrome으로
로컬 현황판을 1280×1000으로 캡처했다. 한국어 제목·77.0%·324/421·집계 시각을
데이터 API와 대조했고, 넓게 빈 중심과 얇은 원호를 확인했다. 우측 원호의 가로
픽셀 구간도 약 5px였다. 브라우저 및 자식 프로세스는 소유 Job에서 정리했다.
앞선 두 실행은 캡처가 없어 실패로 남겼다. 실패 로그의 로컬 주소 차단을 확인해
시험 브라우저의 해석 규칙에서 127.0.0.1만 예외로 둔 뒤 표시를 확인했다.
활성 데스크톱 전환·전역 입력·기존 사용자 브라우저 프로필은 사용하지 않았다.
현황판 확인 항목을 체크한 뒤 `run-04`에서 API와 화면이 함께 325/421·77.2%로
갱신된 것도 확인했다. 이 숫자는 확인 시점의 항목 수이며 고정된 출시 지표가 아니다.


## 깨끗한 별도 인스턴스 복원과 대조 (2026-11-06)

작성: Peppone Choi ([peppone-choi](https://github.com/peppone-choi))

`state_backup.py restore-to ARCHIVE --to 빈_폴더`가 원래 경로를 건드리지 않고 별도 인스턴스 폴더(`characters/`, `world/`, `session/`, 계정이 있으면 `accounts/`)에 복원한다.
- `*.world-owner` 표지는 백업의 월드 경로에 묶여 있는지 확인한 뒤 새 `world/`로 다시 묶는다.
- 대상 폴더가 비어 있지 않거나 백업이 손상됐으면 아무것도 쓰지 않고 거부한다.
- 새 인스턴스를 띄울 환경 변수(`LOGH7_CHARACTERS_FILE` 등)를 출력한다.
- 표지는 바이트로 쓴다. 텍스트 모드로 쓰면 Windows에서 CRLF가 들어가 서버가 경로에 `\r`이 섞였다며 시작하지 못했다(리허설에서 발견해 고침).

계정 파일: 관리 화면에서 추가한 계정과 바꾼 비밀번호도 상태다. 그래서 백업 대상에 넣었다.
- 오프라인 `backup --accounts F`와 실행 중 백업(`LOGH7_ACCOUNTS_FILE`이 있으면 자동)이 같은 `accounts` 그룹·루트로 기록한다.
- `restore`·`restore-to`가 계정 파일도 되돌린다. 계정이 없는 예전 백업은 그대로 읽힌다.

대조: `tools/ops/snapshot_compare.py`가 두 운영 API `/snapshot`에서 시각에 따라 변하는 값(리비전·게임 시각·틱 수)만 가리고 나머지 항목을 비교한다.
- 비교 항목은 기함 위치·CP, 거점 소유와 재고, 부대, 국가 금고, 세션 세대다.
- 완전한 월드 스냅숏이 아닌 입력은 "다름"으로 판정한다. 오류 응답, 빈 응답, 월드가 아직 실리지 않은 기동 직후 응답이 여기에 해당한다.
- `/snapshot`에는 메일 같은 인물 파일 형제 저장소가 없다. 그래서 `--stores ROOT_A ROOT_B`로 정지한 두 인스턴스 폴더의 해당 파일 해시를 따로 비교한다(PR #269 리뷰).

리허설(`work/restore-rehearsal-20261106`, 워크트리 work 폴더, Git 제외):
- 원본은 오프라인 함대 워프 실기 시험 상태다. 읽기만 했으며 원본 10개 파일 해시는 전후 같다.
- 리허설 서버 포트는 49110~49123이다. 원본 게임 클라이언트는 실행하지 않았다.

| 경로 | 복원 | 기동→월드 적재 | RTO | 대조 |
|---|---|---|---|---|
| 오프라인 백업 → 인스턴스 A | 0.17초 | 2.34초 | 2.52초 | — |
| 같은 백업을 D:로 내보낸 사본 → 인스턴스 B | 0.52초 | 3.02초 | 3.53초 | A와 12,576항목 일치 |
| A에서 실행 중 백업(계정 포함, 엔진 정지 40ms) → 인스턴스 C | 0.31초 | 2.84초 | 3.16초 | 백업 시점 A와 12,576항목 일치, 계정 파일 바이트 일치 |
| 손상 사본(세션 저널 1바이트 변조) → 빈 폴더 | — | — | — | `backup damaged: session/session.journal`로 거부, 폴더 미생성 |

- 통신(2026-11-06 추가): 원래 시험 상태에는 메일 저장소가 없었다(`--stores` 0/0). 그래서 메일 시드를 따로 만들어 리허설했다.
  - 깨끗한 인스턴스 `mail-seed`에 실제 `FileCommunicationStore.save`로 메일 3통을 기록했다. 한글·일본어 본문, 읽음 표시, 직무 주소 175→24를 넣었다. 기록은 일회용 시험 코드로 했고 저장소에 남기지 않았다.
  - 이 상태를 계정 포함 오프라인 백업(12개 파일)으로 만들고 `restore-to`로 `mail-clean`에 복원해 기동했다. 월드 적재까지 1.7초였다.
  - 정지 뒤 복원본을 같은 저장 코드로 다시 읽으니 시드와 상태가 같았다(메일 3통·소유자 2명). `snapshot_compare.py --stores`도 메일 파일 1개의 해시 일치를 보였다. 기동만으로는 메일 파일이 다시 쓰이지 않았다.
  - 원본 클라이언트 확인(`work/restore-mail-native-20261106`, 2026-11-06): 위 백업을 `restore-to`로 별도 사본에 복원했다. `multi3`의 Gamma로 정상 로그인·전략 진입 후 편지함을 열어 받은 편지 1통, 발신자 '관찰 한글', 제목 '복원 시험 제목'을 확인했다. 원본 행을 열자 한국어 첫째 줄·일본어 둘째 줄 본문과 발신자 얼굴이 표시됐다. `mail-inbox.png`·`mail-reading.png`는 IEND와 전체 디코딩을 검사한 캡처다.
  - 종료 뒤 같은 저장 코드로 읽어 세 편지의 내용·송수신자·ID·직무 주소가 보존됐고, 개인 편지 1만 미읽음→읽음으로 바뀐 것을 확인했다. 원본 백업의 12개 파일도 다시 검증했다. `run-01`은 지원하지 않는 시험 명령 때문에 중단됐으며 성공으로 세지 않는다. `run-02`는 정상 종료·Job 0·시작 전 무음·원본 해시 보존을 통과했고 시험 JVM도 종료했다. 시험 사본만 사용했고 일반 플레이 저장과 비밀 환경 파일은 접근하지 않았다. 직무 편지는 저장 보존만 대조했으며 원본 화면의 표시 완료로 세지 않는다.
- `restore --force`는 지금 남아 있는 파일만 옮겨 보존한다. 게임 상태 볼륨이 사라지고 계정 파일만 남은 경우에도 복원할 수 있다(PR #269 리뷰, 단위 검사).
- 복원한 인스턴스의 상태는 Codex 실기 기록과 같다. 인물 10002는 2018·항속 700·MCP 1000·이동 4회, 함대 133은 2018, 관찰자 10000은 2017이다.
- 목표 복구 시점(RPO)은 실행 중 백업 주기(`LOGH7_BACKUP_INTERVAL_MINUTES`)다. 감시는 `logh7_backup_last_success_seconds`가 주기의 2배를 넘으면 경보한다(위 절).
- 이번 RTO는 작은 시험 상태(세션 저널 1.4MB) 기준이다. 실제 운영 크기의 RTO는 별도로 다시 잰다.

기동 준비 상태(2026-11-06): 위 리허설에서는 월드 상태 공개 전 0.1~0.3초 동안 `/health`가 200인데 `/snapshot`은 `navigators={}`·`planetStocks=null`인 결함을 찾았다. 이제 세계가 있는 엔진은 첫 전체 월드 스냅숏을 공개하기 전 `STARTING`이며 `/health`는 `503 engine starting`, `logh7_engine_up`은 0이다. 위치·재고가 함께 공개되면 기존 `200 ok`·1이 된다. 저장 실패와 종료는 준비 상태보다 우선하며 각각 기존 503 응답을 유지한다. 세계가 없는 최소 시험 엔진의 정상 응답과 진단용 `/snapshot` 응답 형식은 그대로다.

오프라인 `state_backup.py`와 `server_release.py`는 준비 중·저장 장애·인증 실패 등 HTTP 오류 응답도 실행 중으로 취급한다. 시간초과·이름 조회 실패로 정지를 확인할 수 없어도 작업을 거절하며, 연결이 명시적으로 거부된 경우에만 정지로 판단한다. Windows 루프백의 연결 거부가 약 2초 뒤 도착하는 것을 확인해 요청 제한을 5초로 뒀다. `--health ''`로 확인을 생략하는 기존 명시적 옵션은 유지한다. 준비 중 HTTP 503 때문에 백업·원위치 복원·릴리스 전환을 허용하지 않는 것을 실제 루프백 HTTP와 임시 폴더로 검사했다.

검사: 실제 엔진을 사용하는 HTTP 검사에서 첫 공개 전 503·지표 0, 공개 후 200·지표 1과 인물 위치·거점 소유·국고/지방 금고 보존을 확인했다. 운영 API 33개·엔진 헬스 7개·운영 도구 17개가 로컬에서 통과했다. 세계 편집 시험도 준비 완료 뒤 편집을 시작해 충돌·저장 실패 처리를 계속 검사한다. 운영 도구 검사를 서버 CI에 포함했다. PR #272의 전체 서버·PostgreSQL 실DB·컨테이너 CI도 통과했다. 운영 크기 복원 시간과 직무 편지의 원본 화면 표시는 별도 확인 범위다.
