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

로컬 리허설(`work/gb-20261103`, 단위 검사 3개 통과): 지상전 8대8 상태를 백업하고 서버를 30초 돌려 전력 1000→369로 바꾼 뒤 강제 종료했다. 실행 중 백업 요청은 거부됐고, `--force` 없는 복원도 거부됐다. 복원 뒤 재시작한 서버는 백업 시점(게임 시각 1331121→1330377, 전력 1000)으로 돌아왔다. 복원+재시작의 RTO는 4.6초였다. 이 결과는 오프라인 백업 한 가지이며, 주기 실행·외부 보관·RPO 감시·실행 중 일관 스냅샷은 미구현이다. `evidence:guess`

## 서버 릴리스 전환·롤백 (2026-11-03)

`tools/ops/server_release.py`는 Gradle `installDist` 결과(`server/app/build/install/app`)를 `RELEASES/<이름>`에 해시 매니페스트와 함께 복사하고, `CURRENT`/`PREVIOUS` 파일로 활성 릴리스를 바꾼다. 실행 중(`/health` 응답)이면 전환을 거부하고, 매니페스트와 다른 파일이 있는 릴리스는 활성화하지 않는다. 시작 스크립트는 `classpath` 출력(`<릴리스>/lib/*`)을 `java -cp`에 넘긴다.

업데이트 절차: 서버 정지 → `state_backup.py backup` → `server_release.py install`·`activate` → 시작·확인. 실패하면 정지 → `rollback` → `state_backup.py restore --force` → 시작.

로컬 리허설(`work/release-20261103`, 단위 검사 2개 통과): 정상 릴리스 `good`로 시작·정지하고 상태를 백업한 뒤, 앱 jar를 잘라 만든 `broken` 릴리스를 활성화하자 서버가 `ClassNotFoundException`으로 시작하지 못했다. `rollback`(good 복귀)과 상태 복원 뒤 재시작해 `/health` 응답과 백업 시점 게임 시각을 확인했다. 무중단 전환·자동 상태 확인·원격 배포는 미구현이다. `evidence:guess`

## 운영 지표 `/metrics` (2026-11-03)

운영 API(`127.0.0.1`, 기본 47901·시험 47951)에 Prometheus 텍스트 형식 `/metrics`를 더했다. 엔진 정상 여부(`logh7_engine_up`), 월드 리비전·명령 접수/거부 누계, 세션 세대·게임 시각·참가/접속 수·종료 여부, 전술 인스턴스 수, 진행 중 지상전 거점 수 11개다(검사 `metricsExposeHealthAndCountersInPrometheusText`). 실제 시험 서버에서 `engine_up 1`, 참가 6·접속 0, 지상전 1을 확인했다. 운영 API는 루프백에만 묶여 있어 Compose의 Prometheus 컨테이너는 아직 긁어 갈 수 없다. 같은 호스트의 수집기나 바인딩 정책이 정해진 뒤 `deploy/prometheus/prometheus.yml`에 넣는다. 지연·송신 큐·메모리 지표는 미구현이다. `evidence:guess`

