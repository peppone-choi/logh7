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

업데이트 절차: 서버 정지 → `state_backup.py backup` → `server_release.py install`·`activate` → 시작·확인. 실패하면 정지 → `rollback` → `state_backup.py restore --force` → 시작.

로컬 리허설(`work/release-20261103`, 단위 검사 2개 통과): 정상 릴리스 `good`로 시작·정지하고 상태를 백업한 뒤, 앱 jar를 잘라 만든 `broken` 릴리스를 활성화하자 서버가 `ClassNotFoundException`으로 시작하지 못했다. `rollback`(good 복귀)과 상태 복원 뒤 재시작해 `/health` 응답과 백업 시점 게임 시각을 확인했다. 무중단 전환·자동 상태 확인·원격 배포는 미구현이다. `evidence:guess`

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

