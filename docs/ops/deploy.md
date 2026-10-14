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

**현재 차단 조건:** 기존 Gateway는 `127.0.0.1` 또는 VirtualBox host-only 어댑터만 허용한다. 일반 Docker bridge의 `0.0.0.0`·사설 IP는 거절된다. Kotlin 소스는 변경하지 않았고 `LOGH7_BIND_ADDRESS=127.0.0.1`로 보호 규칙을 보존했다. 이 상태에서는 컨테이너 health는 가능하지만 아래 게시 포트로 호스트 TCP 수락(AC3)은 불가능하다. 현재 PR은 병합/완료 후보가 아니라 계약 조정이 필요한 부분 구현이다. 위 기동 명령으로 실제 게임 연결을 주장하지 않는다. 호스트 공개는 기본 `GAME_BIND_IP=127.0.0.1`, 포트 47900·47902·`${SESSION_PORT:-47903}`이다. `PUBLIC_HOST`는 앱의 `LOGH7_SESSION_ADDRESS`로 전달된다. 외부 공개 또는 운영 배포를 승인하는 문서가 아니다. 운영 API 47901은 컨테이너 loopback 전용이며 호스트에 게시하지 않는다. 이미지 healthcheck는 이 API의 HTTP 200을 확인한다.

`game_sessions`와 `game_captures` named volume을 각각 `/data/sessions`, `/data/captures`에 연결한다. 신규 볼륨은 이미지의 UID 10001 소유 디렉터리에서 초기화된다. 기존 볼륨의 소유권을 임의 변경하지 않는다. `down`은 데이터를 보존하지만 **`down -v`는 데이터를 삭제**한다. CI만 실행별 격리 프로젝트에서 `down -v`와 컨테이너·볼륨 부재 확인을 사용한다. 로컬 실제 데이터에 적용하지 않는다.

## 검증과 제한

GitHub-hosted `container` CI는 이미지 비루트·자료 미포함, Compose 실제 `LOGH7_*`·loopback 계약, 120초 health, 47900·47902·47903 합성 TCP 연결 수락, 항상 실행되는 정리를 검사한다. 기존 `server` CI의 전체 Gradle build는 그대로 유지한다. 호스트 connect만으로는 docker-proxy의 수락을 서버 수락으로 오인할 수 있어 컨테이너 bridge IP의 실제 리스너 도달도 요구한다. TCP 연결 수락은 게임 프로토콜·로그인 성공의 증거가 아니다.

PostgreSQL은 **게임 서버에 미연결**이다. 앱은 파일 기반 세션 저장을 사용하며 DATABASE_* 환경변수·DB 비밀번호·postgres 의존을 제거했다. 근거는 [`Main.kt`](../../server/app/src/main/kotlin/org/logh7/app/Main.kt)의 `LOGH7_SESSION_STORE_DIR` → `FileSessionStore` 선택과 `LOGH7_SESSION_GENERATION_FILE` → `FileSessionGenerationStore` 선택(미지정 시 메모리 구현), 그리고 [`app/build.gradle.kts`](../../server/app/build.gradle.kts)의 앱 의존성이다. 현재 앱 부팅 경로에는 PostgreSQL 접속·DB 환경변수 소비가 없으므로 `depends_on: postgres`는 실제 준비 조건이 아니며 DB secret 마운트도 불필요하다. 제거 범위는 `game-server`에 한정한다. 별도 `postgres` 서비스의 secret·healthcheck·영속 볼륨은 그대로 보존한다. 향후 DB 어댑터를 연결할 때 접속 설정·secret·준비 조건을 별도 계약으로 다시 정의해야 한다. DB 영속·백업 복구·전체 스택/관리 웹 완성은 이 변경으로 검증되지 않는다.

원본 클라이언트 상호운용·게임·오디오 QA **NOT_RUN**, readiness **CLOSED**. GCP·운영 배포·게임 실행 **NOT_RUN**. LOGH-42 전체 이슈 완료를 주장하지 않는다.

## PR45 필수 지적과 최소 후속 결정

작성: 최병호

`Gateway.kt`의 [`checkedBindAddress`](../../server/gateway/src/main/kotlin/org/logh7/gateway/Gateway.kt)는 정확히 `127.0.0.1`을 허용하고, 그 외 주소는 private/link-local IPv4 **및 활성 VirtualBox host-only 어댑터**를 요구한다. 이는 호스트의 published port만이 아니라 **컨테이너 내부 앱 bind**에도 적용된다. Dockerfile·Compose의 loopback을 bridge IP로 바꾸거나 relay로 우회하는 것은 이 불변식의 해결이 아니다. host networking은 운영 API 47901의 호스트 비게시 경계도 바꾸므로 이 수정 범위에서 채택하지 않는다.

기존 hosted `container/game-image` run [38509426503](https://github.com/peppone-choi/logh7/actions/runs/38509426503), [38509422826](https://github.com/peppone-choi/logh7/actions/runs/38509422826)의 health 200 이후 bridge IP TCP47900 `Connection refused`는 이 제한과 일치한다. health 성공만으로 AC3를 통과시킬 수 없으며 기존 bridge 리스너 검사·published TCP 검사를 제거하거나 성공 처리하지 않는다.

후속 결정은 (1) LOGH-42의 bridge 게시 TCP 인수 기준을 유지할 경우, loopback 호스트 공개와 ops API 비게시를 유지하는 **컨테이너 내부 bind 허용 범위·검증 조건의 별도 Gateway 소스 계약**을 확정하거나 (2) 현 불변식을 유지한 내부 loopback 이미지 검증만으로 범위를 명시적으로 재기획하는 것이다. 결정 전 AC3 BLOCKED, PR45 draft·병합 불가를 유지한다. 이 문서는 정책 완화 승인이 아니다.

Compose의 `build: ../server`는 로컬 개발용이며 기본 태그 `logh7/game-server:local`은 운영 승격 이미지가 아니다. 위 `--no-build` 명령은 미리 빌드한 이미지를 사용한다. `--build` 또는 암묵적 로컬 빌드를 운영 배포로 해석하지 않는다.
