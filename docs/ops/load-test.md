# 합성 루프백 접속·정확성 부하 검사

작성: 최병호 · 2026-10-09 · LOGH-46

## 실행

서버 디렉터리에서 `bash ./gradlew :gateway:test`를 실행하면 3명 접속 수명주기, ACK 대기 중 RST, gateway 종료 검사가 기본 검사 대상이다. 50명 검사는 아래 환경 변수를 지정한 경우에만 실행한다. 기존 JDK 25/Gradle 9.8.0을 사용하며 원본 게임·설치 프로그램·외부 서버는 사용하지 않는다.

```bash
LOGH7_LOAD_HARNESS=1 \
LOGH7_LOAD_REPORT_DIR=/tmp/logh7-load-new \
bash ./gradlew :gateway:test --tests org.logh7.gateway.load.LoadHarnessTest --rerun-tasks
```

보고서 경로는 저장소 밖의 새 경로를 지정한다. 같은 이름의 이전 실행 디렉터리를 덮어쓰지 않는다. 지정하지 않으면 JUnit 임시 디렉터리를 사용한다. 활성화 변수와 보고서 경로를 Gradle test 입력으로 등록했으며 `--rerun-tasks`로 같은 설정의 재실행도 강제할 수 있다. fixture 계정과 비밀번호는 코드가 만든 로컬 합성 값이며 실제 계정 정보가 아니다. 파일·캡처·durations·내부 실행 로그는 Git에 추가하지 않는다.

## 실제 검증 경로

공유 frame/envelope/Blowfish/message codec을 사용해 실제 `127.0.0.1` 소켓으로 `0x0034/35/36`, 로그인, 단일 사용 ticket, lobby 조회와 세션 선택, game 로그인, 생성 캐릭터 등록 5단계, 실제 actor 입장 판정과 `0x0206`, 초기 character/unit 응답, 게임 시각 조회까지 확인한다. fixture는 8개 이하 작업 thread로 계정을 순차 묶음 처리하고 50개 game 연결이 동시에 살아 있음을 별도로 확인한다. 50개 로그인이 같은 순간 발생했다고 주장하지 않는다.

한 frame을 나눈 소켓 write와 여러 frame을 합친 write를 사용한다. 사설 raw capture에서 실제 1-byte 입력 chunk도 확인한다. 분할 검사에는 5ms 두 번의 의도적 write 간격이 있어 handshake 지연에 포함된다. 공유 frame decoder의 기존 embedded 분할/병합 검사도 유지한다.

1. 잘못된 fixture 로그인은 거절되고 연결이 닫힌다.
2. 최초 참가자 N명이 수락되고 online/game 채널/admission 매핑이 N개다.
3. 살아 있는 동일 계정의 두 번째 접속은 `ALREADY_ONLINE`, 새 계정의 정원 초과는 `FULL`이다. 실제 wire의 거절 코드와 실제 admission future 판정을 함께 확인한다. 정원은 이 검사 fixture에서 N으로 정하며 운영 규칙 값을 바꾸지 않는다.
4. N개 소켓에 `SO_LINGER=0` RST를 보내고 online/채널/매핑이 0이 되는지 확인한다. 합법적인 오프라인 참가자 N개는 세션 정원에 남는다.
5. 동일 N명이 캐릭터를 다시 생성하지 않고 재접속하며 수락/online이 N개다. 참가자 수는 N에서 증가하지 않는다.
6. 접속 상태에서 실제 Territory 명령으로 세션을 종료한다. 클라이언트 EOF와 online/활성 채널/추적 채널/admission 매핑 0을 모두 확인한다. 오프라인 참가자 N개는 보존한다.

별도 pending-RST 검사는 실제 actor가 입장을 적용한 뒤 fixture가 gateway로 가는 admission 결과만 잠시 보류한다. 성공 ACK가 아직 없는 실제 TCP 소켓을 RST로 닫고, 결과를 풀어도 참가자 1/online 0/채널 0/매핑 0임을 확인한다. actor 큐 자체의 지연 시험이라고 부르지 않는다.

## 관측 seam과 종료

gateway가 소유한 child channel group의 active/tracked 수와 `EngineGameAdmission`의 pending/live 연결 매핑 수를 공개한다. fixture의 `WorldEngine`은 선택적 `ActorTimings` sink를 사용한다. 시뮬레이션 `monotonicMillis`와 계측 `metricNanos`는 독립적이며 운영 기본값의 null sink는 계측 clock을 읽지 않는다. Channel 1024와 매번 send 반환 후 다음 delay를 시작하는 ticker를 유지한다. sink 예외는 상태 처리·저장·ACK·event에 전파하지 않는다. 종료 시 listener와 child 채널을 닫고 event loop를 정리하며 반복 종료는 멱등하다. group은 닫힌 뒤 들어온 child도 닫는다.

기존 허용 주소 검사와 1–65535/서로 다른 포트 조건을 그대로 사용한다. fixture는 루프백 임시 포트 3개를 잠시 예약하고 해제한 후 bind한다. 해제와 bind 사이 경쟁은 남아 있으며 bind 충돌만 최대 5회 시도하고 `bindRetries`를 기록한다. wildcard/외부 주소 허용, 포트 범위 확장, 네트워크 정책 변경은 없다.

## 보고서 읽기와 한계

`small`, `fifty`, `pending-reset` 디렉터리의 JSON `latencies`는 socket 작업 시작부터 완료까지의 실제 `System.nanoTime` 차이를 기록한다. `actorTimings`는 아래 구간을 별도 기록한다. 단위는 nanoseconds다.

| 지표 | 시작 → 끝 |
|---|---|
| `QUEUE_AGE` | enqueue 시도 → actor dequeue 시작(send 대기 포함) |
| `BACKPRESSURED_QUEUE_AGE` | buffer가 가득 찬 제출의 enqueue 시도 → dequeue 시작 |
| `SEND_WAIT` | enqueue 시도 → 성공한 send 반환(즉시 성공도 포함) |
| `TICK_WAKE_LAG` | 이번 delay의 예정 wake → 실제 wake |
| `TICK_DEQUEUE_LAG` | 같은 예정 wake → 해당 AdvanceTo dequeue 시작 |

수동 `AdvanceTo`는 예정 wake가 없어 tick 표본을 만들지 않는다. 음수 차이와 차이 계산 overflow는 표본에서 제외하고 `clockRegressions`에 센다. 각 지표는 첫 4,096개 원시 표본만 보관하고 전체 유효 `count`/`max`와 초과 `dropped`를 유지한다. nearest-rank p50/p95/p99는 보관 표본에서 계산하며 전체 모집단의 백분위라고 해석하지 않는다. 표본 0인 actor 지표는 `NOT_MEASURED`와 null 백분위/max로 출력한다. 실제 부하에서 backpressure가 없으면 해당 지표도 `NOT_MEASURED`다.

socket 원시 표본은 `*-durations.tsv`, actor 표본은 `*-actor-durations.tsv`에 남긴다. 예상 admission 거절 수, RST 요청 수, 각 단계 실제 상태, 예상 밖 작업 오류도 제공한다. drain 이후 queue/send count와 count=sampled+dropped, 최종 online/활성 채널/추적 채널/admission 매핑 0을 검사한다. 마지막 wake 직후 ticker를 취소하면 enqueue 전에 취소될 수 있으며 wake/dequeue 차이는 `cancelledTickWake`로 남긴다. 테스트 assertion/종료 오류도 incomplete/failed로 표시하므로 JSON 일부 수치만으로 합격 처리하지 않는다. Gradle/JUnit 통과와 모든 최종 관측을 함께 확인한다. gateway의 예상 RST 로그는 예상 밖 작업 오류 0과 구분한다.

`join` 표본은 최초/재접속 성공과 예상 거절을 모두 포함한다. `endAndCleanup`은 세션 종료 명령부터 EOF와 최종 상태 확인까지의 벽시계 소요이며 한 번의 수명주기에 표본 1개다. 이를 tick lag나 actor 적용 지연으로 해석하지 않는다. rekey는 `NOT_RUN_UNSUPPORTED`다. 승인된 SLO, 2,000명 용량, 원본 클라이언트의 전략 화면이나 일반 게임 명령 처리, 재키 교환, 운영 대시보드·알림, 여러 호스트·장시간 운영의 성능을 검증한 결과가 아니다.
