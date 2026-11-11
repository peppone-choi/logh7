# 게임 서버 장애 경보

작성: Peppone Choi ([peppone-choi](https://github.com/peppone-choi)) · 2026-11-06 · 체크리스트 14장 "장애 감지·알림"

게임 서버 운영 API `/metrics`를 Prometheus가 수집하고, 같은 규칙으로 경보를 판정해 Alertmanager가 운영자 수신처(웹훅)로 보낸다. 임계값은 운영 정책이다(`evidence:guess`). 원작 근거는 없다.

## 구성

- 규칙: [`deploy/prometheus/game-alerts.yml`](../../deploy/prometheus/game-alerts.yml), 단위 시험 `game-alerts.test.yml`(promtool).
- 경로: [`deploy/alertmanager/alertmanager.yml`](../../deploy/alertmanager/alertmanager.yml).
  - 운영자 웹훅 URL은 Git에 넣지 않는다. `deploy/secrets/alert_webhook_url.txt`(한 줄)를 Compose 비밀로 `url_file`에 넘긴다.
  - 치명 경보는 1시간, 경고는 4시간마다 다시 보낸다.
  - 서버 수집이 끊기면 같은 인스턴스의 다른 경보는 억제한다. 한 장애로 여러 번 호출하지 않기 위해서다.
- 실행: `docker compose -f docker-compose.yml -f docker-compose.metrics.yml --profile game up -d`. 메트릭 덧붙이기 설정이 Prometheus 규칙 파일과 Alertmanager를 함께 띄운다. 실제 Linux Docker에서 이 Compose 조합을 띄운 것은 아직 확인하지 않았다(아래 CI는 바이너리로 같은 규칙과 경로를 검증한다).

## 경보와 지표

| 경보 | 조건 | 등급 |
|---|---|---|
| `LOGH7GameServerUnreachable` | `up{job="game-server"} == 0` 1분 | critical |
| `LOGH7EngineStorageFailed` | `logh7_engine_state{state="storage_failed"} == 1` 1분 | critical |
| `LOGH7EngineUnavailable` | `logh7_engine_up == 0`이지만 저장 실패가 아님(시작 중·정지) 5분 | warning |
| `LOGH7BackupStale` | 백업 주기가 있고, 마지막 성공(없으면 프로세스 시작) 뒤 2배 주기 초과 5분 | warning |
| `LOGH7BackupFailing` | 15분 안에 백업 실패 1회 이상 | warning |
| `LOGH7HeapNearLimit` | 힙 사용/최대 > 90% 10분 | warning |
| `LOGH7StateDiskLow` | 상태 볼륨 여유 < 10% 5분 | warning |
| `LOGH7StateDiskCritical` | 상태 볼륨 여유 < 1 GiB 1분 | critical |
| `LOGH7LoginFailuresHigh` | 10분 안에 로그인 거부 20회 이상 | warning |
| `LOGH7SessionTicketRejections` | 10분 안에 세션 토큰 거부 10회 이상 | warning |
| `LOGH7UpdateFailuresHigh` | 15분 안에 시작했지만 끝나지 않은 업데이트 교환 5회 이상 | warning |
| `LOGH7ConnectionErrorsHigh` | 10분 안에 프로토콜·처리 오류로 끊긴 연결 50회 이상 | warning |
| `LOGH7ActorQueueLag` | 명령 대기 p99 > 1초 5분 | warning |
| `LOGH7TickLagHigh` | 틱 처리 지연 p99 > 2초 5분 | warning |
| `LOGH7CaptureNearLimit` | 캡처가 보존 한도의 90% 초과 30분 | warning |
| `LOGH7AuditLogLarge` | 운영 감사 로그 > 1 GiB 1시간 | warning |
| `LOGH7NotificationOverflow` | 10분 안에 알림 대기열 넘침으로 끊긴 클라이언트 1명 이상 | warning |

이번에 추가한 지표는 다음 여섯 종이다.
- `logh7_engine_state{state=...}`: starting·running·storage_failed·stopped 중 현재 상태만 1이다. `engine_up`은 시작 중·정지·저장 실패에서 모두 0이라, 저장 실패 경보는 이 상태로만 판정한다(Codex 검토).
- `logh7_backup_interval_seconds`: 0은 수동 백업만 쓴다는 뜻이며, 이때 지연 경보는 없다.
- `logh7_backup_failures_total`
- `logh7_process_start_seconds`
- `logh7_state_disk_free_bytes`·`logh7_state_disk_total_bytes`: 월드 상태 폴더, 없으면 세션 저장소가 있는 볼륨을 잰다.

백업 지연을 프로세스 시작부터 재는 이유가 있다. 서버를 새로 띄우면 마지막 백업 시각이 0이어서, 첫 예약 백업 전부터 경보가 울리기 때문이다.

### 게이트웨이 실패 계수 (2026-11-06 2차)

`GatewayCounters`가 게이트웨이마다 다음을 센다. 계정 이름이나 비밀번호는 남기지 않고 횟수만 센다.
- `logh7_login_accepted_total`·`_rejected_total`
- `logh7_session_ticket_rejected_total`: 모르거나 만료됐거나 재사용됐거나 다른 세션·세대로 온 토큰
- `logh7_update_completed_total`·`_failed_total`
- `logh7_connection_errors_total`

업데이트 실패는 "식별(`0x6810`)을 보낸 뒤 버전(`0x6820`) 없이 끊긴 교환"만 센다. TCP 감시(blackbox)가 업데이트 포트에 그냥 접속했다 끊는 것은 실패로 세지 않는다. 로컬 실서버에서 완료 1회·식별 뒤 끊기 6회·단순 접속 1회를 주입하자 완료 1·실패 6으로 집계됐다. 로그인·토큰 계수는 기존 로그인·1회용 토큰 재사용 시험에서 확인했다.

경보 문구에는 `increase()` 값을 넣지 않는다. 보간 때문에 소수가 나와 promtool 비교가 흔들리기 때문이다.

### 월드 액터 지연 (2026-11-06 3차)

엔진은 이미 명령 대기·틱 깨어남/처리 지연·송신 대기를 재고 있었다(`ActorTimings`). 그러나 앱이 수집기를 넘기지 않아 값이 버려졌다. `ActorTimingHistograms`가 이 값을 Prometheus 히스토그램으로 내보낸다.
- 지표는 `logh7_actor_<timing>_seconds_bucket/_sum/_count`와 `logh7_actor_clock_regressions_total`이다. 구간은 1ms~30초다.
- 엔진 코드는 바꾸지 않았다. 앱이 같은 객체를 엔진과 운영 API에 넘긴다.
- 로컬 실서버 약 8초 동안 틱 31개가 기록됐다. 명령 대기 평균 0.18ms, 틱 처리 지연 평균 1.4ms였다.
- 지연 경보는 promtool 가상 표본으로만 확인했다. 실제로 액터를 멈추게 하는 주입은 하지 않았다. CI 주입 시험은 히스토그램이 실제로 쌓이는지만 확인한다.

### 보존 정책 (2026-11-06 4차)

| 대상 | 보존 | 감시 |
|---|---|---|
| 컨테이너 표준 출력 로그 | Compose `json-file` 10 MB × 3 (기존) | — |
| 패킷 캡처(`LOGH7_CAPTURE_DIR`) | `CaptureRetention`: 72시간(`LOGH7_CAPTURE_RETENTION_HOURS`)·512 MiB(`LOGH7_CAPTURE_MAX_MB`), 시작 시와 10분마다 정리 | `logh7_capture_bytes`·`_limit_bytes`·`_removed_files_total`, `LOGH7CaptureNearLimit` |
| 실행 중 백업 | `LOGH7_BACKUP_KEEP`(기본 48, 기존) | 백업 지연·실패 경보 |
| 운영 감사 로그 | 자동 삭제 없음(감사 기록이라 지우지 않음) | `logh7_audit_file_bytes`, `LOGH7AuditLogLarge` |

- 캡처에는 키 교환까지 담겨서 로그인 내용을 복호화할 수 있다. 진단할 때만 켠다. 기본 Compose는 캡처를 켜지 않는다.
- 연결 하나의 파일 묶음(`.tsv`·두 `.bin`)은 함께 지운다. 먼저 보존 시간을 넘은 묶음을 지우고, 그래도 한도를 넘으면 오래된 묶음부터 지운다. 60초 안에 쓰인 묶음은 진행 중인 연결로 보고 남긴다.
- `logh7_capture_bytes`는 마지막 정리 때 잰 값이다(최대 10분 늦다).
- 로컬 실서버에서 5일 된 캡처가 시작 정리로 지워진 것과 지표를 확인했다.

## 실제 주입 시험(CI `game-alerts`)

`deploy/test_game_alerts.py`가 다음을 한 번에 실행한다.
- `promtool check/test rules`
- 해시를 검증한 Prometheus 3.13.3·Alertmanager 0.34.1 바이너리와 실제 게임 서버(`installDist`)를 띄운다. 세션 저장소는 64 MiB tmpfs에 둔다.
- 운영자 수신 대역 웹훅이 받은 경보를 기록한다.

2026-11-06 첫 실행(PR #273):

| 주입 | 운영자 수신 | 소요 |
|---|---|---|
| 처음부터 64 MiB 볼륨 | `LOGH7StateDiskCritical` | 67초 |
| 볼륨을 끝까지 채움(67,043,328바이트) | 4초 뒤 `logh7_engine_up` 0, `LOGH7EngineStorageFailed` | 62초(첫 실행은 `engine_up` 조건) |
| 서버 프로세스 정지 | `LOGH7GameServerUnreachable` | 61초 |

경보는 규칙의 `for` 시간(1분)과 Alertmanager 대기 뒤에 도착했다. `LOGH7EngineUnavailable`의 5분은 현재 경보 조건일 뿐이며, 측정한 정상 시작 시간이 아니다. 큰 월드의 실제 시작 시간을 재서 다시 정한다. 백업 지연·백업 실패·힙 경보는 promtool 가상 표본으로만 확인했다.

## 남은 것

체크리스트 항목 기준으로 남은 것은 다음과 같다.
- 큐/지연: 월드 액터 지연(3차)과 클라이언트 알림 대기열(5차)을 넣었다. 대기열은 요청 처리 중 쌓인 월드 알림 묶음이며, 연결마다 128묶음·512 KiB를 넘으면 끊는다(기존 동작). 5차에서 `logh7_notification_overflow_total`(넘침으로 끊김)과 `logh7_notifications_queued`(전체 대기 묶음 수)를 내보내고 `LOGH7NotificationOverflow` 경보를 더했다. 원본 두 클라이언트의 가속 재접속에서 실제 넘침을 확인했다. [원인과 수정 시험](#가속-월드의-전략-지도-중복-알림-2026-11-11).
- 연속 접속/패치 실패: 서버 쪽 계수와 경보는 2차에서 넣었다. 런처(클라이언트) 쪽 패치 다운로드·적용 실패는 서버가 볼 수 없으므로 아직 남는다.
- 보존 정책: 4차에서 캡처 정리와 감사 로그 용량 경보를 넣었다. 감사 로그 보관·교체 절차(운영자 수동)는 문서로만 정했다.
- 플레이어에게 보이는 오류 표시
- 실제 운영 수신처(채팅/호출 서비스)로의 전달

운영 메모: 실패 계수 경보는 `increase()`라서 Prometheus의 첫 수집 전에 일어난 실패는 세지 못한다. 서버 시작 직후 수집이 붙기 전의 실패가 그렇다. CI 주입 시험도 첫 수집(0)을 확인한 뒤에 주입한다(첫 실행에서 이 순서 때문에 경보가 오지 않았다).

## 가속 월드의 전략 지도 중복 알림 (2026-11-11)

작성자 Peppone Choi ([peppone-choi](https://github.com/peppone-choi)).

매뉴얼 시드 80성계·287거점의 1,000배속 시험에서 두 원본의 재접속이 끊겼고 `logh7_notification_overflow_total`은 2였다. 한 번의 월드 저장에서 여러 무지휘 부대가 이동하면 서버가 같은 최종 전략 지도(`0b0d`)를 부대마다 만들고 보내고 있었다. 수정 후에는 같은 월드 저장을 대상으로 접속마다 지도 한 건을 만들고 각 부대의 전술 퇴장·진입 및 인물 목록 알림은 개별 전달한다. 대기열 상한은 기존 연결 정책을 따른다.

같은 3일차 저장을 복제해 게이트웨이 JAR만 바꿔 두 원본으로 비교했다. 접속별로 같은 게임 시각에 수신한 전략 지도 최대치는 양쪽 모두 137건에서 2건으로 줄었다. 수정본의 두 원본은 5일차까지 연결됐고 종료 직전 대기열 초과와 게이트웨이 연결 오류가 모두 0이었다. 이번 대조의 이전 빌드에서도 실제 끊김은 재현되지 않았다. 두 실행에서 시험 프로세스 정리와 원본 해시 보존을 확인했다(`work/notification-overflow-native-20261111`). 160회 이동 발행의 지도 한 건과 개별 전술 퇴장 순서를 포함해 게이트웨이 검사 24개 및 배포 빌드 통과.
