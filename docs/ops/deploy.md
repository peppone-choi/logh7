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

`LOGH7_BIND_ADDRESS`가 비어 있으면 이미지 진입점이 `hostname -i`의 첫 주소(컨테이너 사설 IP)를 선택한다. 명시한 주소는 그대로 앱에 전달한다. 기존 Gateway의 사설/loopback 주소 보호 규칙은 유지하며 `0.0.0.0`은 거절되므로 사용하지 않는다. 호스트 공개는 기본 `GAME_BIND_IP=127.0.0.1`, 포트 47900·47902·`${SESSION_PORT:-47903}`이다. `PUBLIC_HOST`는 앱의 `LOGH7_SESSION_ADDRESS`로 전달된다. 외부 공개 또는 운영 배포를 승인하는 문서가 아니다. 운영 API 47901은 컨테이너 loopback 전용이며 호스트에 게시하지 않는다. 이미지 healthcheck는 이 API의 HTTP 200을 확인한다.

`game_sessions`와 `game_captures` named volume을 각각 `/data/sessions`, `/data/captures`에 연결한다. 신규 볼륨은 이미지의 UID 10001 소유 디렉터리에서 초기화된다. 기존 볼륨의 소유권을 임의 변경하지 않는다. `down`은 데이터를 보존하지만 **`down -v`는 데이터를 삭제**한다. CI만 실행별 격리 프로젝트에서 `down -v`와 컨테이너·볼륨 부재 확인을 사용한다. 로컬 실제 데이터에 적용하지 않는다.

## 검증과 제한

GitHub-hosted `container` CI는 이미지 비루트·자료 미포함, Compose 실제 `LOGH7_*`·loopback 계약, 120초 health, 47900·47902·47903 합성 TCP 연결 수락, 항상 실행되는 정리를 검사한다. 기존 `server` CI의 전체 Gradle build는 그대로 유지한다. TCP 연결 수락은 게임 프로토콜·로그인 성공의 증거가 아니다.

PostgreSQL은 **게임 서버에 미연결**이다. 앱은 파일 기반 세션 저장을 사용하며 DATABASE_* 환경변수·DB 비밀번호·postgres 의존을 제거했다. DB 영속·백업 복구·전체 스택/관리 웹 완성은 이 변경으로 검증되지 않는다.

원본 클라이언트 상호운용·게임·오디오 QA **NOT_RUN**, readiness **CLOSED**. GCP·운영 배포·게임 실행 **NOT_RUN**. LOGH-42 전체 이슈 완료를 주장하지 않는다.
