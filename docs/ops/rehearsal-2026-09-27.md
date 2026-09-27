# 운영 리허설 기록 — 2026-09-27

작성: 최병호 · LOGH-41/42/43

환경 관찰과 운영 설계는 게임 클라이언트 증거가 아니므로 `evidence:guess`로 분류한다. T5의 E-600 대역은 배정되어 있었으나 별도 reverse 케이스 Evidence 발급은 하지 않았다. 실행 근거는 ops 채팅의 명령 결과와 아래 재현 스크립트이며, 게임 프로토콜 validated 근거로 사용하지 않는다. `evidence:guess`

## 환경과 보존

- `E:\VirtualBox\VBoxManage.exe --version` → `7.2.20r175154`. 기존 `E:\VM\myUbuntu`는 등록되어 있었고 2024년 저장 상태를 정상 재개했다. 실제 NIC는 예상과 달리 bridged였으며 NAT로 바꾼 뒤 원본을 다시 저장했다. 원본 VDI를 삭제하거나 초기화하지 않았다. `evidence:guess`
- 원본 Ubuntu에는 과거 공인 IP 설정이 실행 중 유지됐고 Docker가 없었으며 `sudo`에 암호가 필요했다. 원본을 보존하려고 `E:\VM\myUbuntu-ops` 복제본을 만들었다. 복제본에서만 저장 상태를 버리고 복구 모드로 부팅해 `peppone`의 암호를 비우고 자동 로그인·암호 없는 sudo를 설정했다. NAT DHCP `10.0.2.15` 및 인터넷 연결을 확인했다. `evidence:guess`
- 복제본의 SSH는 VirtualBox NAT 포워딩 `127.0.0.1:2222`→게스트 22로만 접근했다. 키는 git 제외 `deploy/secrets/ops_ssh_key`에 저장했다. VM 바깥으로 DB·Grafana·Prometheus 포트를 열지 않았다. `evidence:guess`

## 실제 실행 결과

| 검사 | 결과 |
|---|---|
| Ubuntu 24.04 apt 설치 | Docker Engine 29.1.3, Compose 2.40.3, OpenSSH 설치 성공 |
| Compose 구성 | 기본 및 `game` 프로필 `docker compose config --quiet` 종료 코드 0 |
| 이미지 다운로드·기동 | PostgreSQL 18.6, Prometheus 3.13.3, Grafana 13.2.2 pull/up 성공 |
| DB | `pg_isready` 성공, `ops_rehearsal` 표식 삽입·조회 성공 |
| HTTP 준비 상태 | Prometheus `/-/ready`, Grafana `/api/health` 모두 HTTP 200 |
| Compose stop/start | 세 서비스 재시작 성공, DB 표식 유지 |
| Grafana 데이터 소스 | 인증 API에서 UID `prometheus`와 URL `http://prometheus:9090` 확인 |
| 게스트 재부팅 | Ubuntu 자동 업데이트 종료를 기다린 뒤 정상 부팅; Compose 세 서비스 자동 기동, PostgreSQL healthy, DB 표식 유지 |

검증용 스크립트는 [`deploy/rehearsal.sh`](../../deploy/rehearsal.sh)다. 완료 표식 `COMPOSE_REHEARSAL_OK`를 확인했다. 최초 Grafana 비밀 파일은 0600이라 UID 472·GID 0 컨테이너가 읽지 못해 API 401이 났다. 파일 그룹을 0, 권한을 0640으로 바꾸고 관리자 암호를 설정한 뒤 전체 시험이 통과했다. `evidence:guess`

## 검증되지 않은 범위

게임 서버 이미지는 아직 없고 Compose `game` 프로필은 자리표시자다. 세션 리스너 47903도 미구현이며 내장 Ktor 운영 API 127.0.0.1:47901과 구분한다. WAL 외부 아카이브, 5분 RPO 복원, 50명 부하, GCP Terraform validate/plan 및 서울 SKU 견적은 미검증이다. GCP API 생성·apply·과금 작업, git 및 Linear 수정은 수행하지 않았다. `evidence:guess`

reverse-skill 통합 제안: 환경 관찰과 Compose 리허설 로그를 리드의 timeline/workitems에 남기고, 실제 게임 서버·백업 복원 시험이 끝난 뒤 실행 로그와 해시를 Evidence로 등록한다. 바이너리 대상 분석을 수행하지 않아 validated Finding을 추가하지 않는다. `evidence:guess`
