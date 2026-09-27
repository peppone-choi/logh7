# ADR-0005 개정안 — GCP 운영 기준

작성: 최병호 · 확인일: 2026-09-27 · LOGH-41

상태: 리드 반영용 초안. 사용자 D-2(GCP 전제·현재 로컬 연습)에 따라 기존 ADR의 자택 서버 우선/회선 확인 대기 문구를 교체한다. 인프라 선택과 비용은 게임 원작 근거가 아닌 운영 설계이므로 `evidence:guess`로 표시한다. `evidence:guess`

## 결정 제안

- 서울 `asia-northeast3`, 초기 단일 존 `asia-northeast3-a`, Compute Engine `e2-standard-2`(2 vCPU·8 GiB)를 출발점으로 한다. 50명 수용은 아직 부하 시험하지 않았으며 보장하지 않는다. JVM·DB·관측 도구의 메모리와 틱 지연 측정 후 증설한다. 도쿄는 서울 가용성/비용/지연 비교 대안이다. `evidence:guess`
- Ubuntu 24.04 LTS, Docker Compose, Kotlin/JVM+Netty+코루틴 및 내장 Ktor 운영 API를 유지한다. Spring을 추가하지 않는다. Next.js 포털은 별도 연결 단계로 남긴다. `evidence:guess`
- 리전 고정 외부 IPv4 하나를 VM에 붙이고 로그인 서버와 세션 서버 광고 주소에 같은 공인 주소를 사용한다. 원시 TCP 47900/47902를 열며 세션 포트는 현재 **47903 설계값**이다. 실제 세션 리스너는 미구현이다. 내장 Ktor 운영 API 127.0.0.1:47901은 비공개이며 세션 포트로 쓰지 않는다. 실제 서버 리스너 확정 뒤 방화벽·Compose·LGLoginOK를 함께 갱신한다. `evidence:guess` 기존 기본 포트와 LGLoginOK 주소 반환은 ADR-0002의 E-005/E-006/E-018 정적 근거다. `evidence:client`
- 초기 알파는 승인된 플레이어 CIDR만 허용한다. 운영 API·DB·Prometheus·Grafana는 공개하지 않고 관리자는 IAP SSH 포워딩을 사용한다. 공개 포털의 TLS/443은 구현 후 별도 규칙을 추가한다. `evidence:guess`
- 부팅 PD 20 GiB, 데이터 `pd-balanced` 50 GiB를 분리하고 VM 삭제 보호 및 데이터 디스크 보존을 둔다. 일일 스냅샷 14일 보관은 디스크 손상 대응용이며 WAL 기반 DB 복구를 대체하지 않는다. [백업 설계](backup.md)를 운영 선행 조건으로 둔다. `evidence:guess`

## PostgreSQL 선택 비교

| 선택 | 장점 | 부담/제약 | 초기 판단 |
|---|---|---|---|
| VM 자체 PostgreSQL | 같은 Compose로 로컬 연습, 별도 DB 인스턴스 요금 없음 | VM과 장애 영역 공유, 패치·WAL·복구 시험 직접 수행 | 비공개 리허설 우선 |
| Cloud SQL PostgreSQL | 관리형 백업·PITR 및 HA 선택 가능 | 별도 CPU/RAM/스토리지 비용, 사설 접속·권한 구성 필요 | 운영 인력/가용성 요구 증가 시 전환 |

표는 운영 설계 판단이다. Cloud SQL을 선택하더라도 PITR 활성화·보관 기간·복원 시험 없이는 5분 RPO 달성을 선언하지 않는다. `evidence:guess`

## 월 비용 모델 (USD, 730시간)

2026-09-27 [Compute 공식 가격](https://cloud.google.com/products/compute/pricing), [디스크/스냅샷](https://cloud.google.com/compute/disks-image-pricing?hl=en), [네트워크](https://cloud.google.com/vpc/network-pricing), [Cloud SQL 가격](https://cloud.google.com/sql/pricing?hl=en)을 확인했다. 가격 페이지의 동적 지역 표에서 서울 SKU 단가를 확정 추출하지 못했으므로 **아래는 예산 가정이며 서울 공식 견적이 아니다**. 과금 승인 전 계산기에서 서울·SKU·통화·날짜를 고정한 견적을 저장한다. `evidence:guess`

| 항목 | 계산 가정 | 월 추정 |
|---|---|---:|
| e2-standard-2 | 시간당 $0.08~0.11 예산 가정 ×730 | $58.40~80.30 |
| 부팅+데이터 PD | 70 GiB × $0.10~0.15/GiB월 가정 | $7.00~10.50 |
| 사용 중 IPv4 | 공식 표시 $0.005/시간 ×730 (무료 1시간 무시) | $3.65 |
| 스냅샷+외부 WAL 보관 | 변경량/보관에 따른 임시 예산 | $5~15 |
| 인터넷 송신 | 100 GiB × $0.12~0.20 가정 | $12~20 |
| 합계 | 자체 PostgreSQL, 세금·환율·할인 제외 | **$86.05~129.45** |

트래픽과 WAL 발생량은 미측정이다. 실제 합계는 `730×VM단가 + PD용량×지역단가 + IPv4 + 증분스냅샷 + WAL저장·요청 + 목적지별 송신량`으로 갱신한다. VM 중지 후에도 디스크/주소 등 요금이 남을 수 있으므로 비용 종료는 리소스별 확인이 필요하다. `evidence:guess`

Cloud SQL 공식 페이지에 표시된 기본 지역 예시 단가(vCPU $0.0413/h, RAM $0.007/GiB·h)로 2 vCPU·8 GiB는 `730×(2×0.0413+8×0.007)=$101.18/월`의 **계산 자원만** 추가된다. 같은 예시 HA는 $202.36이며 DB 저장·백업·네트워크는 별도다. 이는 서울 견적이 아니고 지역·에디션·머신 계열에 따라 달라진다. `evidence:guess`

## 완료 조건과 남은 일

Compose 기동·재시작 영속성, 50명 부하와 틱 지연, 세션 재접속, 백업 복원 후 300초 이하 손실, GCP 견적 및 Terraform plan을 확인해야 공개 배포 결정을 할 수 있다. 현재 [리허설 기록](rehearsal-2026-09-27.md)의 환경 제약으로 런타임 검증은 미완료다. GCP 생성·apply·과금 및 호스트 설정 변경은 수행하지 않았다. `evidence:guess`
