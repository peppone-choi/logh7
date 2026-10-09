# 백업·복구 설계

작성: 최병호 · 2026-09-27 · LOGH-43

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
