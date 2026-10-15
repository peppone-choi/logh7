# LOGH-42 게임 서버 컨테이너 (로컬 개발·CI)

Java 25 JDK에서 `:app:installDist`로 빌드하고 Java 25 JRE에서 UID/GID 10001로 실행한다. Docker context는 `server/`이며 `.dockerignore`가 main 소스·빌드 정의·wrapper만 허용한다. 원본 바이너리·캡처·계정 파일을 이미지에 넣지 않는다.

## 명령

승인된 개발 환경에서 저장소 루트 기준:

```sh
docker build -t logh7/game-server:local server
docker compose -f deploy/docker-compose.yml --profile game config --quiet
docker compose -f deploy/docker-compose.yml --profile game up -d --no-build --wait --wait-timeout 120 game-server
docker compose -f deploy/docker-compose.yml --profile game ps
docker compose -f deploy/docker-compose.yml --profile game stop game-server
```

명시적으로 `game-server`만 선택하면 postgres·prometheus·grafana는 기동하지 않으며 비밀번호 파일도 이 서비스에 마운트하지 않는다. 전체 스택은 [deploy README](../../deploy/README.md)의 별도 리허설 준비가 필요하다.

**PR45 범위(권장안 C):** Linear LOGH-42의 원문 완료 조건은 "`docker compose up` 으로 전체 기동, 헬스체크 통과, 운영 문서 docs/ops/deploy.md"이며 bridge TCP 수락(AC3)은 포함하지 않는다. 이 PR은 그중 game 이미지·프로필의 기동과 내부 loopback 검증을 다룬다. 전체 스택 완료는 별도 검증이 필요하다. 기존 Gateway의 loopback/VirtualBox host-only 정책과 `LOGH7_BIND_ADDRESS=127.0.0.1`을 유지하며 Kotlin·AGENTS 정책을 변경하지 않는다.

게임 포트 47900·47902·`${SESSION_PORT:-47903}`은 컨테이너 내부 loopback 전용이다. Docker bridge에서 도달할 수 없어 무효였던 game-server `ports:` 게시를 제거했다. 호스트 게임 연결은 제공하지 않으며 **미검증**이다. `PUBLIC_HOST`는 앱의 `LOGH7_SESSION_ADDRESS`로 전달되는 값일 뿐 호스트 연결을 보장하지 않는다. 운영 API 47901도 컨테이너 loopback 전용·비게시이며 이미지 healthcheck가 HTTP 200을 확인한다. 외부 공개 또는 운영 배포를 승인하는 문서가 아니다.

`game_sessions`와 `game_captures` named volume을 각각 `/data/sessions`, `/data/captures`에 연결한다. 신규 볼륨은 이미지의 UID 10001 소유 디렉터리에서 초기화된다. 기존 볼륨의 소유권을 임의 변경하지 않는다. `down`은 데이터를 보존하지만 **`down -v`는 데이터를 삭제**한다. CI만 실행별 격리 프로젝트에서 `down -v`와 컨테이너·볼륨 부재 확인을 사용한다. 로컬 실제 데이터에 적용하지 않는다.

## 검증과 제한

GitHub-hosted `container` CI는 이미지 비루트·자료 미포함, Compose 실제 `LOGH7_*`·game 포트 비게시 계약, 120초 Healthy 대기, 내부 `127.0.0.1:47901`의 HTTP 200, 내부 `127.0.0.1:47900/47902/47903`의 실제 TCP 수락과 항상 실행되는 정리를 검사한다. 기존 `server` CI의 전체 Gradle build는 그대로 유지한다. 다른 서비스의 기존 loopback 게시 설정은 보존한다. 내부 TCP 수락은 호스트 도달성·게임 프로토콜·로그인 성공의 증거가 아니다.

PostgreSQL은 **게임 서버에 미연결**이다. 앱은 파일 기반 세션 저장을 사용하며 DATABASE_* 환경변수·DB 비밀번호·postgres 의존을 제거했다. 근거는 [`Main.kt`](../../server/app/src/main/kotlin/org/logh7/app/Main.kt)의 `LOGH7_SESSION_STORE_DIR` → `FileSessionStore` 선택과 `LOGH7_SESSION_GENERATION_FILE` → `FileSessionGenerationStore` 선택(미지정 시 메모리 구현), 그리고 [`app/build.gradle.kts`](../../server/app/build.gradle.kts)의 앱 의존성이다. 현재 앱 부팅 경로에는 PostgreSQL 접속·DB 환경변수 소비가 없으므로 `depends_on: postgres`는 실제 준비 조건이 아니며 DB secret 마운트도 불필요하다. 제거 범위는 `game-server`에 한정한다. 별도 `postgres` 서비스의 secret·healthcheck·영속 볼륨은 그대로 보존한다. 향후 DB 어댑터를 연결할 때 접속 설정·secret·준비 조건을 별도 계약으로 다시 정의해야 한다. DB 영속·백업 복구·전체 스택/관리 웹 완성은 이 변경으로 검증되지 않는다.

원본 클라이언트 상호운용·게임·오디오 QA **NOT_RUN**, readiness **CLOSED**. GCP·운영 배포·게임 실행 **NOT_RUN**. LOGH-42 전체 이슈 완료를 주장하지 않는다.

## 호스트 게임 연결의 후속 계약

작성: 최병호

[`Gateway.kt`의 `checkedBindAddress`](../../server/gateway/src/main/kotlin/org/logh7/gateway/Gateway.kt)는 정확히 `127.0.0.1`을 허용하며 그 외 주소에는 private/link-local IPv4 및 활성 VirtualBox host-only 어댑터를 요구한다. Docker bridge는 이 어댑터가 아니다. `0.0.0.0`, relay, host networking으로 우회하지 않는다.

기존 hosted run [38525302714](https://github.com/peppone-choi/logh7/actions/runs/38525302714)의 health 200 이후 bridge TCP47900 `Connection refused`는 앱이 loopback에 바인딩된 결과였다. PR45에서 자체 추가한 bridge·published TCP 검사를 내부 loopback 검사로 정정한 것이며 기존 main 정책·CI를 완화한 것이 아니다. Linear 원문에 없는 bridge AC3 BLOCKED를 이 PR의 인수 기준으로 유지하지 않는다.

호스트 게임 연결이 필요하면 별도 이슈에서 bind 계약과 `AGENTS.md`의 localhost/host-only 제한 개정 여부를 프로젝트 소유자가 결정해야 한다. PR45는 최신 hosted `container/game-image`·`server/build` 성공과 독립 리뷰·검증 전까지 draft·병합 불가를 유지한다. readiness CLOSED와 제품 QA NOT_RUN은 변하지 않는다.

Compose의 `build: ../server`는 로컬 개발용이며 기본 태그 `logh7/game-server:local`은 운영 승격 이미지가 아니다. 위 `--no-build` 명령은 미리 빌드한 이미지를 사용한다. `--build` 또는 암묵적 로컬 빌드를 운영 배포로 해석하지 않는다.
