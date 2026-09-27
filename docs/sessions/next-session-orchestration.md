# 세션 2 오케스트레이션 — 요구사항과 프롬프트

작성: 최병호 · 2026-09-27 · 선행: [BOOTSTRAP-REPORT](../BOOTSTRAP-REPORT.md)

## A. 요구사항

### A-1. 사용자 결정 (2026-09-27 확정)

| # | 결정 | 다음 세션 반영 |
|---|---|---|
| D-1 | **G: 드라이브 재연결 → VirtualBox 사용** | 시작 시 `G:\VBox\VBoxManage.exe` 확인. 없으면 멈추고 질문 |
| D-2 | **자택 공인 IP 없을 가능성 → GCP 배포 전제, 지금은 로컬에서 연습** | ADR-0005 §5·§6 개정. GCP 리소스 생성·과금 작업은 하지 않고 IaC/스크립트 초안까지만. 로컬 연습 호스트는 기존 Ubuntu VM(`E:\VM\myUbuntu`) 또는 Docker |
| D-3 | **reverse-skill 기록은 로컬 main에만** | 2026-09-27 병합 완료(원격보다 1커밋 앞섬, push 안 함). 이후도 동일 |
| D-4 | **용어는 당분간 "일본어 직역 + 나무위키 검색 표기"** | CLAUDE.md §5·glossary.md 기준 개정. 이타카판 실물 대조는 보류 |
| D-5 | (에이전트 결정, ADR-0001 개정 2) **게임 서버는 Kotlin/JVM 유지, Spring 제외(내장 Ktor 운영 API)**, 웹 Next.js, 플레이어용 런처·패치 적용기·DLL은 Rust 1순위, 프로토콜은 스키마→코덱 생성 | 사용자가 C#에 익숙하면 C#/.NET으로 역전 → 세션 시작 시 한 번 확인. Linear LOGH-17·45·26, P6 설명의 Spring 표기 정정 |

### A-2. 환경 사실 (2026-09-27 확인)

| 항목 | 상태 | 의미 |
|---|---|---|
| C: 여유 | **7.3 GB** | C: 쓰기 금지 수준. 캐시(Gradle·npm·pip·Docker·VM)까지 전부 E:로 |
| E: 여유 | 440 GB | VM·ISO·도구·빌드 산출물 위치 |
| VirtualBox | 7.0.18 (`G:\VBox`, G: 재연결 필요) | 게임 클라이언트용 Windows 게스트 새로 생성 |
| 기존 VM | `E:\VM\myUbuntu`(VirtualBox, Ubuntu) | GCP Compute Engine 대역(로컬 리눅스 서버)으로 활용 가능 |
| Windows 게스트 ISO | **없음** | Microsoft 공식 배포처에서 받아야 함(프롬프트에서 사전 승인) |
| docker / wsl / gcloud | 명령 존재 | Hyper-V 계열이 켜져 있으면 VirtualBox 성능·3D 가속 저하 가능(체크포인트) |
| Ghidra 12.1.2 / JDK 21 / Python 3.11 | 사용 가능 | JDK 25는 서버용으로 `E:\Tools`에 추가 |

### A-3. 세션 목표와 완료 조건

목표: **P2 M2.1 "스텁 서버 첫 패킷"** 도달, P1 잔여 정리, 결정 D-1~D-5 반영.

| # | 완료 조건 | Linear |
|---|---|---|
| G-1 | 결정 D-1~D-5가 문서(ADR-0005, CLAUDE.md, glossary)·Linear에 반영 | LOGH-5, 17, 26, 38, 41, 45 |
| G-2 | 서버 골격 빌드 통과(JDK 25, Gradle Kotlin 멀티모듈 protocol/engine/gateway/ops-api/persistence/app, Spring 없음) + CI 1개 | LOGH-17 |
| G-3 | Blowfish 변형 구현이 클라이언트 테이블에서 뽑은 벡터로 단위 테스트 통과, 봉투·키 교환 상태기계 골격 | LOGH-61 |
| G-4 | Windows 게스트 VM(자동 로그인·빈 비밀번호, ja-JP 로캘, 호스트 전용망) + 원본 설치 + 설치 전후 비교 | LOGH-5, 6 |
| G-5 | VM 안 원본 클라이언트를 인자로 실행 → 스텁 TCP 접속 + 첫 0x34 블록 기록(Evidence) → 프레이밍 Finding validated | LOGH-18, 20, 22 |
| G-6 | MsgDat(HFWR) 왕복 도구 — 22개 파일 바이트 동일 재생성 테스트 | LOGH-12 |
| G-7 | 패치노트 이력 문서, 부록 CSV 전 페이지 대조 | LOGH-58, 15 |
| G-8 | GCP 전제 로컬 Compose 리허설(Ubuntu VM 또는 Docker) + GCP IaC 초안(적용 안 함) + 비용 추정 | LOGH-41, 42, 43 |
| G-9 | 세션 보고서 `docs/sessions/2026-09-28-report.md`, PR 머지, Linear 갱신, reverse-skill 체크리스트 | — |

G-4·G-5는 D-1(G: 재연결)과 Windows ISO 확보에 달려 있다. 막히면 나머지를 끝까지 진행하고 보고서에 사유·조치를 쓴다.

### A-4. 트랙 구성 (서브에이전트 5 + 메인)

| 트랙 | 담당 | 일 | Linear | 쓰기 경로 |
|---|---|---|---|---|
| T0 | **메인(리드)** | 결정 반영, VM 구축·설치 비교·인자 실행·패킷 기록 통합, 케이스 관리, 교차검증, 커밋·PR·Linear | LOGH-5, 6, 20, 22, 38(기준 개정), 41 | 전체 |
| T1 | server-core | 서버 골격(Spring 없음, 내장 Ktor), 메시지 스키마→코덱 생성 골격, MPS 암호 계층(Blowfish 변형·봉투·키 교환), 스텁 게이트웨이·패킷 기록기, 업데이트 스텁 | LOGH-17, 61, 18, 19 | `server/`, `docs/protocol/server-notes.md` |
| T2 | re-deep | Ghidra: 키 교환 phase1~4 필드 맵·테스트 벡터, Login(0x0010)·LGLoginOK(0x7001) 본문 오프셋, 업데이터 0x68xx/0x80xx 본문, 전략 지도 데이터 위치 | LOGH-11, 14, (61 지원) | `docs/protocol/`, `docs/re/`, `server/protocol/src/test/resources/vectors/`, 케이스 폴더 |
| T3 | l10n | HFWR 왕복 도구, 용어집 채우기(직역+나무위키), 로캘·글꼴 문자열 패치 도구(사본 전용) | LOGH-12, 38, 36(준비) | `tools/l10n/`, `docs/l10n/` |
| T4 | content-docs | Wayback 패치노트 33건 요약·규칙 변경 이력, 부록 CSV 전 페이지 대조 | LOGH-58, 15 | `docs/manual/` |
| T5 | ops | ADR-0005 GCP 개정안, docker-compose, Ubuntu VM에서 Compose 리허설, GCP IaC 초안(적용 금지), 비용 추정, 백업 설계 | LOGH-41, 42, 43 | `deploy/`, `docs/ops/` |

의존: T1의 LOGH-61은 T2 벡터가 필요, T1의 LOGH-19는 T2의 LOGH-11 결과가 필요, 메인의 LOGH-20은 T1 스텁 + VM이 필요. 메인은 대기하지 않고 VM 구축을 병행한다.

### A-5. 지난 세션에서 배운 운영 규칙

- 서브에이전트 지시문은 짧고 구체적으로. 긴 지시문은 전달 중 끊긴 적이 있음.
- 서브에이전트가 쓴 파일이 중간에 잘린 사례 2건 → **쓴 뒤 파일 끝을 확인**하게 하고, 메인은 커밋 전 꼬리·문법 확인.
- Evidence 번호 대역을 트랙별로 나눈다(충돌 방지).
- case-review 계약: Finding은 `- severity/status/confidence/location` 한 줄씩, Path에는 `- path_type:`.
- 원문(매뉴얼·사이트·게임 텍스트) 장문 전재 금지 → 커밋 전 "연속 일본어 40자 이상" 검사.

---

## B. 붙여넣기용 프롬프트

아래 블록을 다음 세션에 그대로 붙여넣는다.

````markdown
# LOGH7 부활 프로젝트 — 세션 2 (오케스트레이션)

## 0. 역할·범위
너는 이 프로젝트의 리드다. 작업 루트는 `E:\logh7`(github peppone-choi/logh7). 이번 세션은 **서브에이전트 병렬 오케스트레이션을 명시적으로 승인**한다(Agent 도구로 서브에이전트 5개 병렬, 필요하면 Workflow 도구를 써도 된다. 규모는 10개 미만). 메인은 대기하지 말고 자기 트랙을 병행한다.

목표: **P2 M2.1 "스텁 서버 첫 패킷"** 도달 + P1 잔여 정리 + 아래 결정 D-1~D-5 반영.

## 1. 먼저 읽을 것 (읽음/실행함 구분해 보고)
1. `E:\logh7\CLAUDE.md`, `docs/sessions/next-session-orchestration.md`(이 세션의 요구사항 전체), `docs/BOOTSTRAP-REPORT.md`, `docs/PLAN.md`, `docs/adr/0001~0005`, `docs/protocol/protocol-draft.md`, `docs/re/connection-flow.md`, `docs/l10n/text-format.md`
2. `E:\reverse-skill\RULES.md` → `skills\MASTER-ROUTING.md` (분석 작업은 master-route → case-init → PRIMARY SKILL → tool-index 순서)
3. Linear `LOGH` 팀 사이클 1·2 이슈

## 2. 확정된 결정 (2026-09-27 사용자)
- D-1: 사용자가 G: 드라이브를 재연결한다 → **VirtualBox 7.0.18(`G:\VBox`)** 로 게임용 Windows 게스트를 만든다. 시작 시 `G:\VBox\VBoxManage.exe --version` 확인, 실패하면 멈추고 물어본다.
- D-2: 자택 공인 IP가 없을 가능성 → **GCP 배포를 전제로 로컬에서 연습**한다. GCP 리소스 생성·과금 작업은 하지 않는다(IaC·스크립트 초안과 비용 추정까지만). 로컬 연습 호스트는 기존 `E:\VM\myUbuntu`(Linux, GCP VM 대역) 또는 Docker.
- D-3: reverse-skill 회신(field-journal·references·index)은 **로컬 main에만** 커밋, push 금지.
- D-4: 용어는 당분간 **"일본어 직역 + 나무위키 검색 표기"**. 이타카판 실물 대조는 보류. CLAUDE.md §5와 `docs/l10n/glossary.md` 기준을 이렇게 개정한다.
- D-5: 스택은 ADR-0001 개정 2 — **게임 서버 Kotlin/JVM(Spring 없음, 내장 Ktor 운영 API)**, 웹 Next.js, 플레이어용 런처·패치 적용기·DLL은 Rust 1순위, 프로토콜은 스키마→코덱 생성. **세션 시작 시 사용자에게 C# 숙련 여부만 한 번 확인**(익숙하면 C#/.NET으로 재검토). Linear LOGH-17·45·26과 P6 설명의 "Spring Boot" 표기를 정정한다.

## 3. 사전 승인 (이 프롬프트를 붙여넣으면 이번 세션에 한해 승인)
- `E:\logh7` 커밋·push·PR·셀프 머지. `E:\reverse-skill` 로컬 main 커밋(push 금지).
- reverse-skill 스크립트: master-route, case-init(정적은 `-Preset offline-sample`, VM 동적 케이스는 `-Preset own-system`=lab_only), case-guard, append-evidence, case-review, refresh-tool-index, 필요한 도구만 bootstrap.
- 다운로드·설치(모두 **E:\** 에): Temurin JDK 25, Gradle, Maven Central 의존성, Docker Hub 공식 이미지(postgres 등), **Microsoft 공식 배포처의 Windows 10 x64 ISO**(평가판 또는 정품 ISO, 공식 microsoft.com 도메인만).
- VirtualBox: `E:\VM\logh7-win` 에 Windows 게스트 생성. **자동 로그인 + 빈 비밀번호**(사용자에게 로그인·비밀번호를 묻지 않는다), 시스템 로캘 ja-JP. OS 설치·일본어 기능 설치 동안만 NAT, **게임 실행 전 호스트 전용망으로 전환**. 스냅샷 `clean`/`installed`.
- 게임 설치·클라이언트 실행은 **VM 안에서만**. 스텁 서버는 localhost/호스트 전용 어댑터에만 바인딩.
- `E:\VM\myUbuntu` 안에 Docker 설치·Compose 기동(리허설용, 해당 VM은 NAT 인터넷 허용).
- Linear `LOGH` 팀 이슈·문서·댓글 생성/수정.

**승인하지 않은 것(필요하면 멈추고 질문)**: GCP 리소스 생성·과금, 호스트 시스템 설정 변경(Windows 기능·호스트 방화벽 규칙 등), 사용자 데이터 삭제, reverse-skill 원격 push, 외부 공개 게시, 원본 게임 파일 수정(가공은 사본에만).

## 4. 트랙 배치 (서브에이전트 5 + 메인)
| 트랙 | 담당 | 할 일 | Linear | 쓰기 경로 | Evidence 번호 |
|---|---|---|---|---|---|
| T0 | 메인 | D-1~D-5 반영(ADR-0005 개정, CLAUDE.md §5, Linear), VM 구축(LOGH-5)·설치 전후 비교(LOGH-6)·인자 실행 접속(LOGH-20)·첫 0x34 블록 기록·프레이밍 validated(LOGH-22), 교차검증·커밋·PR | 5, 6, 20, 22, 38, 41 | 전체 | E-100~ |
| T1 | server-core | 서버 골격(Kotlin 2.4 + JDK 25 + Netty 4.2 + 코루틴, **Spring 없음**, Gradle Kotlin DSL, 모듈 protocol/engine/gateway/ops-api(Ktor 3.6)/persistence/app, GitHub Actions 빌드), 메시지 스키마(YAML)→Kotlin 코덱 생성 골격, MPS 암호 계층(Blowfish 변형·봉투·키 교환 상태기계), 스텁 게이트웨이 47900 + 패킷 기록기, 업데이트 스텁 47902. 외부 입력은 엔진 커맨드 큐로만 | 17, 61, 18, 19 | `server/`, `docs/protocol/server-notes.md` | E-200~ |
| T2 | re-deep | Ghidra headless: 키 교환 phase1~4 필드 맵과 **테스트 벡터**(클라이언트의 변형 P/S 테이블 추출 포함), Login(0x0010)·LGLoginOK(0x7001) 본문 오프셋, 업데이터 0x68xx/0x80xx 본문(LOGH-11), 전략 지도 데이터 위치(LOGH-14) | 11, 14, 61 지원 | `docs/protocol/`, `docs/re/`, `server/protocol/src/test/resources/vectors/`, `work/logh7-client-triage/` | E-300~ |
| T3 | l10n | HFWR/GFWR 파서·재생성 도구와 22개 파일 바이트 동일 테스트, 용어집 채우기(일본어 직역 + 나무위키 표기, 출처 URL), 로캘 문자열(`Japanese`)·글꼴명(`ＭＳ ゴシック` @0x0076e240) 패치 도구(사본 전용, 원본 해시 보존) | 12, 38, 36 준비 | `tools/l10n/`, `docs/l10n/` | E-400~ |
| T4 | content-docs | Wayback `bin/backview.cgi?ct=update` 33건 요약(원문 전재 금지)·규칙 변경 이력 표·미구현 후보 갱신, 부록 CSV p.56–100 페이지 이미지 대조 | 58, 15 | `docs/manual/` | E-500~ |
| T5 | ops | ADR-0005 개정안(GCP Compute Engine 전제: 리전·머신·고정 IP·방화벽 TCP 포트·영구 디스크·백업), `deploy/docker-compose.yml`, `E:\VM\myUbuntu`에서 Compose 리허설, GCP IaC 초안(terraform 또는 gcloud 스크립트, **apply 금지**), 비용 추정(웹 확인·출처), 백업 설계 | 41, 42, 43 | `deploy/`, `docs/ops/` | E-600~ |

의존: T1 LOGH-61 ← T2 벡터, T1 LOGH-19 ← T2 LOGH-11, T0 LOGH-20 ← T1 스텁 + VM.

## 5. 공통 규칙 (서브에이전트 지시문에 반드시 포함)
- 문서는 한국어, 작성자 **최병호**, 코드·파일명·명령은 영어. 모든 주장에 `evidence:manual|client|guess`.
- 매뉴얼·사이트·게임 텍스트 원문 장문 전재 금지(용어·짧은 구절만). 게임 자산·추출 텍스트 커밋 금지.
- **C: 여유 7GB** → C:에 아무것도 쓰지 않는다. `GRADLE_USER_HOME=E:\Tools\gradle-home`, npm/pip 캐시·Docker 데이터·VM·ISO 모두 E:.
- 도구 경로는 `E:\reverse-skill\skills\tool-index.md` 만 신뢰. 새 도구는 `E:\Tools\`.
- 서브에이전트는 git 금지, 지정 경로에만 쓴다. **파일을 쓴 뒤 끝부분을 다시 읽어 잘림이 없는지 확인**한다. 끝나면 10줄 이내 요약(결과·산출물·미해결).
- Evidence는 케이스 폴더에 `append-evidence.ps1` 로, 트랙별 번호 대역 사용. Finding `validated` 는 독립 증거 2개 이상(정적1+동적1 권장).
- 클라이언트는 VM에서만 실행. VM은 자동 로그인·빈 비밀번호.
- 최신 버전·가격은 웹으로 확인하고 출처를 남긴다.

## 6. 멈추고 물어볼 것
G: 미연결 / Windows ISO 선택·라이선스 문제 / VM에서 3D 가속·DirectX 8 문제로 클라이언트가 뜨지 않을 때(대안 VMware 사용 여부) / Hyper-V 공존으로 VirtualBox가 동작하지 않을 때 / GCP 과금 / 호스트 시스템 설정 변경 / 삭제 / 범위를 바꾸는 결정.

## 7. 완료 조건과 보고
- [ ] G-1~G-9 (docs/sessions/next-session-orchestration.md A-3) 달성 또는 미달성 사유·조치 기록
- [ ] 커밋은 작은 단위 한국어 Conventional Commits, 기능 브랜치 → PR → 셀프 머지
- [ ] `case-review --verify-hashes --strict` PASS, reverse-skill 완료 체크리스트(보고서·다이어그램·field-journal·references·index — 로컬 main)
- [ ] Linear: 완료 이슈 Done + 산출물 댓글, 새 발견은 이슈로
- [ ] 보고서 `docs/sessions/2026-09-28-report.md`: ① 가정 ② 완료(읽음/실행함 구분) ③ 못 한 것과 이유·필요 조치 ④ 가장 큰 리스크 3개·다음 세션 첫 작업 3개 ⑤ 사용자 미결 항목 ⑥ 출처
````
