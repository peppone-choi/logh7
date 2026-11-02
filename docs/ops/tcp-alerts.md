# localhost TCP 도달성 알림 평가

작성: Peppone Choi · 2026-10-11 · LOGH-46

## 인수 단위

동일 호스트에서 LOGH7의 TCP listener가 연결 가능한지 관측하고, 1분 동안 연결 실패가 지속되면 Prometheus alert가 `pending`에서 `firing`으로 전환하며 복구 후 사라지는 경로다. probe 수집 자체가 실패하면 listener 상태를 알 수 없으므로 별도 alert로 표시한다. 수신자에게 알림을 보내는 Alertmanager/메일/메신저 경로, 운영 dashboard, tick 지연 목표/SLO는 후속 인수조건이다.

TCP 연결 확인은 원본 게임의 handshake·로그인·게임 동작을 검증하지 않는다. 이번 실제 시험 대상은 검증기가 소유한 localhost TCP fixture다. 원본 클라이언트 호환성이나 운영 서버에 대한 외부 도달성을 시험한 결과로 해석하지 않는다.

## 구성과 관측

`deploy/prometheus/prometheus-tcp.yml`은 네이티브 Prometheus와 blackbox exporter가 같은 localhost에서 실행되는 별도 opt-in 설정이다. 기존 Docker 리허설의 Prometheus/Grafana 구성과 인증·비밀 파일을 수정하지 않는다. exporter HTTP API도 localhost에만 bind한다. 새 인증 설정, admin/reload API 활성화, 외부 probe 대상은 추가하지 않는다.

| endpoint label | 기본 TCP 대상 |
|---|---|
| login | 127.0.0.1:47900 |
| update | 127.0.0.1:47902 |
| session | 127.0.0.1:47903 |

세션 포트를 별도로 지정한 경우 실행 전에 동일 호스트의 승인된 target을 명시적으로 대조한다. `probe_success=0`은 connect 실패이고 `up=0`은 해당 probe HTTP 응답 수집 실패다. 두 규칙 모두 지속 1분 이후 firing한다. 이 1분은 로컬 리허설의 장애 유지 조건(`evidence:guess`)이며 게임/tick SLO로 확정한 값이 아니다. Prometheus 자체의 종료나 알림 전달 경로 장애를 이 구성만으로 탐지할 수는 없다.

[Blackbox 공식 구성](https://github.com/prometheus/blackbox_exporter/blob/v0.29.0/CONFIGURATION.md)에 따른 TCP connect module을 사용하며 게임 명령이나 인증 정보를 전송하지 않는다. [Prometheus alert 규칙](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)의 `for` 유지 조건을 사용한다.

## localhost에서 활성화

프로그램 경로와 새 TSDB 데이터 경로를 지정하고 저장소 루트에서 아래 두 프로세스를 실행한다. 예시는 loopback bind를 유지한다. 기존 운영 프로세스나 리허설 스택을 재시작하지 않는다.

```sh
blackbox_exporter --config.file=deploy/blackbox/tcp.yml \
  --web.listen-address=127.0.0.1:9115
prometheus --config.file=deploy/prometheus/prometheus-tcp.yml \
  --web.listen-address=127.0.0.1:9091 \
  --storage.tsdb.path=/tmp/logh7-tcp-prometheus-new
```

Prometheus의 `/api/v1/alerts` 또는 localhost 웹 UI에서 label과 상태를 확인한다. 이 설정에는 알림 수신자가 연결되어 있지 않다. listener가 아직 시작되지 않은 상태에서 opt-in 설정을 실행하면 TCP 장애 alert가 발생한다.

## 검증

Prometheus 3.13.3은 기존 리허설과 같은 버전이며, blackbox exporter는 [0.29.0 공식 릴리스](https://github.com/prometheus/blackbox_exporter/releases/tag/v0.29.0)를 사용했다. CI는 [Prometheus 3.13.3 공식 릴리스](https://github.com/prometheus/prometheus/releases/tag/v3.13.3)와 exporter 릴리스의 SHA-256을 고정하고 검사한 후 binary를 실행한다.

```sh
python3 deploy/test_tcp_alerts.py \
  --prometheus /path/to/prometheus \
  --promtool /path/to/promtool \
  --blackbox-exporter /path/to/blackbox_exporter \
  --report-dir /tmp/logh7-tcp-alert-test-new
```

먼저 `promtool`로 설정과 [결정적 rule 시험](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)을 확인한다. 1분 직전의 pending, firing, 짧은 장애, 복구, 다른 job 제외, probe 표본이 없는 수집 장애를 검사한다.

그다음 실제 binary 두 개와 TCP fixture 세 개를 새로운 임시 포트에서 실행한다. production 규칙·5초 scrape/evaluation·1분 `for`를 유지하고 target/HTTP listener 포트만 fixture 포트로 바꾼다. session listener만 닫아 다른 두 endpoint는 정상인 상태에서 pending/firing/복구를 확인한 뒤, exporter를 종료하고 재시작해 probe 수집 장애의 pending/firing/복구도 확인한다. 보통 2–3분이 소요된다. 전체 JVM 시험이나 50명 게임 부하를 반복하는 검사는 아니다.

새 report 디렉터리의 `result.json`에는 규칙 SHA-256, 각 단계의 실제 HTTP query/alert 응답, 각 fixture가 받은 TCP 연결 수와 소유 프로세스 종료 여부를 남긴다. 같은 report 경로를 덮어쓰지 않고 실행 중 실패도 `FAILED`로 기록한다. binary 경로·원본 설정 검사 또는 report 디렉터리 생성 전에 실패하면 보고서는 생성되지 않으며 명령이 실패한다. 검증기가 시작한 프로세스와 fixture만 정리하며 TSDB/보고서를 삭제하지 않는다. `.github/workflows/tcp-alerts.yml`은 이 변경 경로에만 실행되며 같은 실제 경로를 검사한다.
