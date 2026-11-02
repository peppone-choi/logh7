# 로컬 운영 리허설

작성: Peppone Choi · 2026-09-27 · LOGH-42

이 구성은 PostgreSQL·Prometheus·Grafana와 선택형 `game` 프로필의 Kotlin 게임 서버 이미지를 정의한다. 게임 서버 빌드·단독 기동·실제 환경 변수·볼륨·검증 제한은 [게임 서버 문서](../docs/ops/deploy.md)를 따른다. PostgreSQL은 게임 서버에 미연결이다. 기본 포트 게시는 Prometheus·Grafana의 호스트 loopback에 한정하며 게임 포트는 게시하지 않는다. `evidence:guess`

## 버전 확인

2026-09-27 공식 페이지 확인: [PostgreSQL 18.6](https://www.postgresql.org/support/versioning/), [Prometheus 3.15.0 최신 / 3.13.3 LTS](https://prometheus.io/download/), [Grafana 13.2.2](https://grafana.com/grafana/download). 리허설은 Prometheus LTS를 선택했다. Ubuntu 복제 VM에서 세 이미지 pull과 기동에 성공했다. 운영 배포용 immutable digest 고정은 후속 작업이다. `evidence:guess`

## 재개 순서 (Ubuntu VM 안에서)

1. `E:\VM\myUbuntu`에 해당하는 VM과 Docker 데이터 디스크 위치를 먼저 확인한다. 호스트 Docker 대체 시 이미지·볼륨·가상 디스크가 E:에 있음을 확인하기 전 pull/up을 하지 않는다. 이번 작업에서 호스트 설정은 변경하지 않았다. `evidence:guess`
2. 이번 Ubuntu 24.04 복제 VM에서는 `sudo apt-get update` 후 Ubuntu 저장소의 `docker.io docker-compose-v2 openssh-server`를 설치했다(확인 버전: Docker 29.1.3, Compose 2.40.3). 새 환경에서 공급사 패키지를 택할 때는 [Docker 공식 apt 설치 안내](https://docs.docker.com/engine/install/ubuntu/)를 따른다. 설치는 VM 안에서만 수행한다. `evidence:guess`
3. 저장소를 VM으로 복사한 뒤 `deploy`에서 서로 다른 무작위 비밀번호 두 개를 `secrets/postgres_password.txt`, `secrets/grafana_password.txt`에 한 줄씩 저장한다. 디렉터리는 0700, PostgreSQL 파일은 0600으로 제한한다. Grafana 컨테이너는 UID 472·GID 0이므로 Grafana 파일은 소유자=운영 계정, 그룹=0, 권한 0640으로 설정한다(`sudo chgrp 0 secrets/grafana_password.txt && chmod 640 secrets/grafana_password.txt`). Docker Compose secrets는 이 로컬 파일을 마운트하며 외부 비밀 저장소가 아니다. `evidence:guess`

```sh
sudo docker compose config --quiet
sudo docker compose pull postgres prometheus grafana
sudo docker compose up -d postgres prometheus grafana
sudo docker compose ps
sudo docker compose exec -T postgres pg_isready -U logh7 -d logh7
curl --fail http://127.0.0.1:9090/-/ready
curl --fail http://127.0.0.1:3000/api/health
sudo docker compose stop
sudo docker compose start
bash rehearsal.sh
```

재시작 후 DB에 넣은 리허설 표식과 Grafana 데이터 소스가 유지되어야 한다. `down -v`는 데이터 삭제이므로 복구 시험에 사용하지 않는다. VM의 Grafana 접근은 SSH 포워딩 또는 제한된 호스트 전용망 구성을 사용한다. `evidence:guess`

2026-09-27 리허설에는 기존 `E:\VM\myUbuntu`를 보존하고 `E:\VM\myUbuntu-ops` 복제본을 사용했다. 원본은 저장 상태이며, 복제본의 NIC는 NAT, SSH 포워딩은 호스트 `127.0.0.1:2222`에만 바인딩했다. 비밀번호 없는 자동 로그인과 sudo는 복제본의 실습 편의를 위한 설정이다. [실행 결과](../docs/ops/rehearsal-2026-09-27.md)를 참조한다. `evidence:guess`

## 게임 서버 단독 개발·CI

[게임 서버 컨테이너 문서](../docs/ops/deploy.md)의 `docker build` 후 `--profile game up ... game-server`를 사용한다. `GAME_SERVER_IMAGE` 기본값은 `logh7/game-server:local`이며 운영 immutable digest 승격은 별도 작업이다. 앱은 `LOGH7_*`만 읽는다. 파일 세션·캡처 볼륨을 사용하며 PostgreSQL 접속·DB 비밀번호는 연결하지 않는다. `Main.kt`의 파일/메모리 저장 선택에 맞춰 게임 서비스의 PostgreSQL `depends_on`과 secret만 제거했으며, 별도 postgres 서비스의 secret·healthcheck·볼륨은 보존했다. 직접 소스 근거와 호스트 게임 연결의 별도 후속 계약은 [게임 서버 문서](../docs/ops/deploy.md)를 따른다. 게임 포트 47900·47902·47903(세션 포트 기본값)은 컨테이너 내부 loopback 전용·비게시이며, 호스트 게임 연결은 제공하지 않고 검증 NOT_RUN이다. 운영 API 47901도 컨테이너 loopback healthcheck 전용·비게시다.

현재 PR45 범위는 internal-loopback image validation이다. DB 영속·전체 스택·원본 클라이언트 상호운용·게임·오디오 QA NOT_RUN, readiness CLOSED. GitHub CI의 컨테이너 내부 `127.0.0.1` TCP 수락은 호스트 도달성·프로토콜·로그인 또는 실제 게임 성공의 증거가 아니다. 운영/GCP 배포 NOT_RUN이며 LOGH-42 전체 완료를 주장하지 않는다.

GCP 적용은 [IaC 초안](gcp/README.md), 복구는 [백업 설계](../docs/ops/backup.md), 실제 검증 기록은 [리허설 기록](../docs/ops/rehearsal-2026-09-27.md)을 따른다. `evidence:guess`

## LOGH-46 TCP 알림 평가

별도 네이티브 localhost probe의 장애 유지·복구 검증은 [TCP 알림 인수 단위](../docs/ops/tcp-alerts.md)를 따른다. 이 구성은 기존 Compose·인증 설정을 변경하지 않으며 알림 수신자 전달, 운영 dashboard와 SLO는 후속 범위다.
