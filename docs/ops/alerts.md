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

이번에 추가한 지표는 다음 여섯 종이다.
- `logh7_engine_state{state=...}`: starting·running·storage_failed·stopped 중 현재 상태만 1이다. `engine_up`은 시작 중·정지·저장 실패에서 모두 0이라, 저장 실패 경보는 이 상태로만 판정한다(Codex 검토).
- `logh7_backup_interval_seconds`: 0은 수동 백업만 쓴다는 뜻이며, 이때 지연 경보는 없다.
- `logh7_backup_failures_total`
- `logh7_process_start_seconds`
- `logh7_state_disk_free_bytes`·`logh7_state_disk_total_bytes`: 월드 상태 폴더, 없으면 세션 저장소가 있는 볼륨을 잰다.

백업 지연을 프로세스 시작부터 재는 이유가 있다. 서버를 새로 띄우면 마지막 백업 시각이 0이어서, 첫 예약 백업 전부터 경보가 울리기 때문이다.

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
- 큐/지연 경보: 송신 큐·틱 지연 지표가 아직 없다.
- 연속 접속/패치 실패 경보: 로그인·패치 실패 계수가 아직 없다.
- 로그·캡처·백업 보존 정책과 용량 경보 전체
- 플레이어에게 보이는 오류 표시
- 실제 운영 수신처(채팅/호출 서비스)로의 전달
