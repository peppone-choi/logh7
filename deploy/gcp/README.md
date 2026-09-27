# GCP IaC 검토 초안

작성: 최병호 · 2026-09-27 · LOGH-41 · `evidence:guess`

`main.tf`는 Compute Engine 단일 VM, 전용 VPC·서브넷, 고정 IP, 데이터 PD와 일일 스냅샷, 게임 TCP 방화벽, IAP SSH 경로를 제안한다. **apply/create를 실행하지 않았다.** Terraform도 로컬에 없어 validate/plan을 실행하지 않았다. 버전 범위는 호환성 제약 초안이며 최신 버전 확인 또는 provider 스키마 검증을 뜻하지 않는다. `evidence:guess`

승인 후의 검토 절차는 `terraform init -backend=false` → `terraform fmt -check` → `terraform validate` → 자격증명·프로젝트 확인 → `terraform plan -out=review.tfplan`이다. init은 provider 다운로드를 수반하므로 Windows에서는 `TF_DATA_DIR`, `TF_PLUGIN_CACHE_DIR`, `TEMP`, `TMP`를 E: 경로로 지정한다. plan은 리소스를 생성하지 않지만 API 읽기와 로컬 state/plan 파일 생성을 수반한다. 별도 승인 전 apply는 하지 않는다. `evidence:guess`

예상 계획은 VM 1, VPC 1, 서브넷 1, 주소 1, 데이터 디스크 1, 스냅샷 정책 1, 정책 연결 1, IAP 방화벽 1이다. `player_cidrs`가 비어 있으면 게임 방화벽은 생성하지 않으며 승인된 CIDR을 넣으면 1개 추가한다. 공개 운영은 별도 결정 후 CIDR을 넓힌다. `evidence:guess`

다음 항목은 이 초안에 구현되지 않았다: API 활성화·IAM/IAP 접근 권한, 원격 state 저장소, OS 이미지 불변 ID 고정, 데이터 디스크 포맷·마운트, Docker 설치/배포, TLS 웹 포털, WAL 외부 저장소와 업로드 권한, 예산 알림. 디스크 마운트를 확인하기 전 Compose를 시작하면 부팅 디스크에 데이터가 생기므로 금지한다. VM 전용 서비스 계정은 필요한 외부 백업 저장소 권한만 붙여 별도 설계한다. `evidence:guess`

포트 47903은 세션용 설계값이며 실제 세션 리스너는 미구현이다. LGLoginOK에는 고정 외부 IP와 실제 세션 포트를 반환하도록 서버와 맞춘다. 내장 Ktor 운영 API 127.0.0.1:47901은 비공개로 유지한다. VM 안 Compose의 `GAME_BIND_IP`는 GCP 운영 시 내부 NIC 또는 `0.0.0.0`으로 설정하되 Prometheus/Grafana는 loopback을 유지한다. DB 5432 및 운영 API는 인터넷 방화벽에 열지 않는다. `evidence:guess`

일일 PD 스냅샷만으로 5분 RPO를 충족하지 않는다. [백업 설계](../../docs/ops/backup.md)의 외부 WAL 보관과 복구 시험이 공개 운영 선행 조건이다. 스냅샷 시작 시간은 UTC 18:00(KST 다음날 03:00), 보관 14일이다. `evidence:guess`

참고: [Terraform Google provider](https://registry.terraform.io/providers/hashicorp/google/latest/docs), [IAP TCP 전달](https://cloud.google.com/iap/docs/using-tcp-forwarding), [디스크 스냅샷](https://cloud.google.com/compute/docs/disks/snapshots). 적용 전 공식 스키마·요금·권한을 다시 검토한다. `evidence:guess`
