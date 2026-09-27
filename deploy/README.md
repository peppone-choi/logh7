# 로컬 운영 리허설

작성: 최병호 · 2026-09-27 · LOGH-42

이 구성은 PostgreSQL·Prometheus·Grafana를 기동하고 게임 서버는 `game` 프로필의 자리표시자로 남긴다. 실제 서버 이미지, 환경 변수 처리, 지표 API 구현은 별도 연결 작업이다. 기본값은 호스트 loopback에만 포트를 게시한다. `evidence:guess`

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

## 실제 게임 서버 연결 전

`GAME_SERVER_IMAGE`를 빌드된 불변 digest로 지정하고 `--profile game`으로 시작한다. `DATABASE_PASSWORD_FILE` 등은 **제안된 계약**이며 서버가 읽는지 확인해야 한다. `POSTGRES_USER=logh7`는 초기화 관리자이므로 실제 게임 접속에는 별도 최소 권한 DB 역할을 생성한 뒤 교체한다. 세션 47903은 임시값이며 실제 세션 리스너는 미구현이다. 내장 Ktor 운영 API는 127.0.0.1:47901에서 동작하므로 이 포트를 공개 세션 포트로 사용하지 않는다. 운영 API는 호스트에 게시하지 않는다. `evidence:guess`

GCP 적용은 [IaC 초안](gcp/README.md), 복구는 [백업 설계](../docs/ops/backup.md), 실제 검증 기록은 [리허설 기록](../docs/ops/rehearsal-2026-09-27.md)을 따른다. `evidence:guess`
