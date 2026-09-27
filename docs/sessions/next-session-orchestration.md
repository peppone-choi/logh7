# 세션 2 오케스트레이션 — 요구사항과 프롬프트 (Codex CLI 기준)

작성: 최병호 · 2026-09-27 · 선행: [BOOTSTRAP-REPORT](../BOOTSTRAP-REPORT.md) · 실행 도구: **OpenAI Codex CLI**(Windows 네이티브, 멀티 에이전트)

구성: **A 요구사항** → **B 실행 준비(사용자)** → **C 리드 프롬프트**(Codex에 붙여넣기) → **D 트랙 프롬프트 T1~T5**(리드가 서브에이전트에 넘김)

---

## A. 요구사항

### A-1. 결정 사항

| # | 결정 | 반영 |
|---|---|---|
| D-1 | **G: 드라이브 재연결 → VirtualBox 사용** (사용자) | 시작 시 `G:\VBox\VBoxManage.exe --version` 확인. 없으면 멈추고 질문 |
| D-2 | **GCP 배포 전제, 지금은 로컬에서 연습** (사용자) | ADR-0005 §5·§6 개정. GCP 리소스 생성·과금 금지(IaC·스크립트 초안·비용 추정까지). 로컬 연습 호스트: `E:\VM\myUbuntu`(Linux, GCP VM 대역) 또는 Docker |
| D-3 | **reverse-skill 기록은 로컬 main에만** (사용자) | 2026-09-27 병합 완료(원격보다 1커밋 앞섬). push 금지 |
| D-4 | **용어: 일본어 직역 + 나무위키 검색 표기** (사용자) | AGENTS.md §5 반영 완료. glossary.md 채우기 |
| D-5 | **게임 서버 Kotlin/JVM + Netty + 코루틴, Spring 없음(내장 Ktor)**, 웹 Next.js, 클라이언트측 도구 Rust 1순위, 프로토콜 스키마→코덱 (ADR-0001 개정 2) | 사용자가 C#에 익숙하면 C#/.NET으로 재검토 → 세션 시작 시 한 번 확인. Linear LOGH-17·45·26, P6 설명의 "Spring" 표기 정정 |

### A-2. 환경 사실 (2026-09-27 확인)

| 항목 | 상태 | 의미 |
|---|---|---|
| C: 여유 | **7.3 GB** | C:에 쓰기 금지 수준. 도구·캐시·VM·ISO 모두 E: |
| E: 여유 | 440 GB | |
| VirtualBox | 7.0.18 (`G:\VBox`, G: 재연결 필요) | 게임용 Windows 게스트 새로 생성 |
| 기존 VM | `E:\VM\myUbuntu` (Ubuntu) | GCP VM 대역으로 Compose 리허설 |
| Windows 게스트 ISO | 없음 | Microsoft 공식 배포처에서 확보(사전 승인) |
| docker / wsl / gcloud | 설치됨 | Hyper-V 계열이 켜져 있으면 VirtualBox 성능·3D 저하 가능 |
| Ghidra 12.1.2 / JDK 21 / Python 3.11 | 사용 가능 | JDK 25는 `E:\Tools`에 추가 |

### A-3. 목표와 완료 조건

목표: **P2 M2.1 "스텁 서버 첫 패킷"** 도달, P1 잔여 정리, D-1~D-5 반영.

| # | 완료 조건 | Linear |
|---|---|---|
| G-1 | D-1~D-5가 문서(ADR-0005 개정, glossary)·Linear에 반영 | LOGH-5, 17, 26, 38, 41, 45 |
| G-2 | 서버 골격 빌드 통과(JDK 25, Gradle Kotlin 멀티모듈 protocol/engine/gateway/ops-api/persistence/app, Spring 없음) + GitHub Actions 빌드 1개 | LOGH-17 |
| G-3 | Blowfish 변형이 클라이언트 테이블에서 뽑은 벡터로 단위 테스트 통과, 봉투·키 교환 상태기계 골격 | LOGH-61 |
| G-4 | Windows 게스트 VM(자동 로그인·빈 비밀번호, ja-JP 로캘, 호스트 전용망) + 원본 설치 + 설치 전후 비교 | LOGH-5, 6 |
| G-5 | VM 안 원본 클라이언트를 인자로 실행 → 스텁 TCP 접속 + 첫 0x34 블록 기록(Evidence) → 프레이밍 Finding validated | LOGH-18, 20, 22 |
| G-6 | MsgDat(HFWR) 왕복 도구 — 22개 파일 바이트 동일 재생성 테스트 | LOGH-12 |
| G-7 | 패치노트 이력 문서, 부록 CSV 전 페이지 대조 | LOGH-58, 15 |
| G-8 | GCP 전제 로컬 Compose 리허설 + GCP IaC 초안(적용 안 함) + 비용 추정 | LOGH-41, 42, 43 |
| G-9 | 보고서 `docs/sessions/2026-09-28-report.md`, PR 머지, Linear 갱신, reverse-skill 체크리스트 | — |

G-4·G-5는 G: 재연결과 Windows ISO에 달려 있다. 막히면 나머지를 끝까지 하고 보고서에 사유·조치를 쓴다.

### A-4. 트랙 구성 (Codex 리드 1 + 서브에이전트 5)

| 트랙 | 역할명(`agents.*`) | 일 | Linear | 쓰기 경로 | Evidence |
|---|---|---|---|---|---|
| T0 | 리드(메인) | D-1~D-5 반영, VM 구축·설치 비교·인자 실행 접속·첫 0x34 기록·프레이밍 validated, 통합·검증·git·Linear | 5, 6, 20, 22, 38, 41 | 전체 | E-100~ |
| T1 | `server-core` | 서버 골격, 스키마→코덱, MPS 암호 계층, 스텁 게이트웨이·패킷 기록기, 업데이트 스텁 | 17, 61, 18, 19 | `server/`, `docs/protocol/server-notes.md` | E-200~ |
| T2 | `re-deep` | 키 교환 필드 맵·테스트 벡터, Login/LGLoginOK 본문, 업데이터 본문, 전략 지도 데이터 | 11, 14, 61 지원 | `docs/protocol/`, `docs/re/`, `server/protocol/src/test/resources/vectors/`, `work/logh7-client-triage/` | E-300~ |
| T3 | `l10n` | HFWR/GFWR 왕복 도구, 용어집, 로캘·글꼴 패치 도구(사본 전용) | 12, 38, 36 준비 | `tools/l10n/`, `docs/l10n/` | E-400~ |
| T4 | `content-docs` | 패치노트 33건 요약·변경 이력, 부록 CSV 대조 | 58, 15 | `docs/manual/` | E-500~ |
| T5 | `ops` | ADR-0005 GCP 개정안, compose, Ubuntu VM 리허설, IaC 초안, 비용, 백업 | 41, 42, 43 | `deploy/`, `docs/ops/` | E-600~ |

의존: T1(LOGH-61) ← T2 벡터 · T1(LOGH-19) ← T2(LOGH-11) · T0(LOGH-20) ← T1 스텁 + VM. 서브에이전트는 같은 작업 폴더를 공유하므로 **쓰기 경로가 겹치지 않게** 한다.

### A-5. Codex 실행 환경 요구사항 (2026-09-27 웹 확인)

| 항목 | 요구 | 근거 |
|---|---|---|
| 프로젝트 규칙 | Codex는 `AGENTS.md`를 읽는다 → 저장소 루트 `AGENTS.md`가 규칙 단일 원천(CLAUDE.md는 이를 가져옴) | [Config reference](https://learn.chatgpt.com/docs/config-file/config-reference) |
| 멀티 에이전트 | `[features] multi_agent = true`, `[agents] max_concurrent_threads_per_session = 5`, 역할 `agents.<name>.description` | 동일 |
| 샌드박스 | Windows 네이티브 샌드박스는 실험 단계. VBoxManage·Docker·Ghidra·Gradle이 막힐 수 있어 이번 세션은 `sandbox_mode = "danger-full-access"` + `approval_policy = "on-request"` 권장(대안: workspace-write + writable_roots + network_access) | [Windows sandbox](https://learn.chatgpt.com/docs/windows/windows-sandbox), [Codex on Windows 가이드](https://codex.danielvaughan.com/2026/04/01/codex-cli-windows-native-sandbox-wsl/) |
| Linear | `codex mcp add linear --url https://mcp.linear.app/mcp` → `codex mcp login linear`(OAuth, 사용자가 직접). 연결이 안 되면 리드가 `docs/sessions/2026-09-28-linear-updates.md`에 반영할 변경을 기록 | [Linear MCP docs](https://linear.app/docs/mcp), [Linear Codex 통합](https://linear.app/integrations/codex-mcp) |
| 셸 | PowerShell(네이티브). WSL로 돌리면 `E:\`→`/mnt/e`, VBoxManage·Ghidra는 Windows 실행 파일이라 네이티브 권장 | 위 Windows 가이드 |
| 설정 예시 | [codex-config.example.toml](codex-config.example.toml) — 사용자가 `~/.codex/config.toml`에 병합 | — |

### A-6. 지난 세션에서 배운 운영 규칙

- 서브에이전트 지시문은 짧고 구체적으로(긴 지시문이 전달 중 끊긴 적 있음).
- 서브에이전트가 쓴 파일이 중간에 잘린 사례 2건 → 쓴 뒤 끝부분 확인, 리드는 커밋 전 꼬리·문법 확인.
- case-review 계약(Finding 필드 한 줄씩, Path `- path_type:`).
- 원문 장문 전재 금지 → 커밋 전 "연속 일본어 40자 이상" 검사.

---

## B. 실행 준비 (사용자가 할 일)

1. **G: 드라이브 연결** → PowerShell에서 `G:\VBox\VBoxManage.exe --version` 이 나오는지 확인.
2. **Codex 설정 병합**: [codex-config.example.toml](codex-config.example.toml) 내용을 `~/.codex/config.toml`에 합친다(샌드박스 A/B 중 선택).
3. **Linear 연결(선택)**: `codex mcp add linear --url https://mcp.linear.app/mcp` → `codex mcp login linear` (브라우저에서 승인).
4. **실행**: PowerShell에서 `codex --cd E:\logh7` → 아래 C 블록을 붙여넣는다.
5. (선택) Windows ISO를 이미 갖고 있으면 경로를 C 블록 §3에 적는다. 없으면 에이전트가 Microsoft 공식 배포처에서 받는다.

---

## C. 리드 프롬프트 (Codex에 붙여넣기)

````markdown
# LOGH7 부활 — 세션 2 (Codex 리드)

너는 이 프로젝트의 리드다. 작업 루트 `E:\logh7`(github peppone-choi/logh7). 규칙은 저장소 `AGENTS.md`를 따른다(이미 로드됨; 안 됐으면 먼저 읽어라). 이번 세션은 **멀티 에이전트 오케스트레이션을 명시적으로 승인**한다: 서브에이전트 5개(`server-core`, `re-deep`, `l10n`, `content-docs`, `ops`)를 병렬로 띄우고, 각자에게 `docs/sessions/next-session-orchestration.md` §D의 해당 트랙 프롬프트를 그대로 넘겨라. 리드는 기다리지 말고 T0를 병행하라. 멀티 에이전트 도구를 쓸 수 없으면 T1→T2→T3→T4→T5를 네가 순서대로 수행하고, 보고서에 그렇게 했다고 적어라.

## 1. 시작 순서
1. 읽기(읽음/실행함 구분해 보고): `AGENTS.md`, `docs/sessions/next-session-orchestration.md`(요구사항 전체), `docs/BOOTSTRAP-REPORT.md`, `docs/PLAN.md`, `docs/adr/0001~0005`, `docs/protocol/protocol-draft.md`, `docs/re/connection-flow.md`, `docs/l10n/text-format.md`, `E:\reverse-skill\AGENTS.md` → `RULES.md` → `skills\MASTER-ROUTING.md`.
2. 사용자에게 **한 번만** 묻는다: "C#/.NET에 익숙하신가요?"(ADR-0001 재검토 조건). 답이 없으면 Kotlin으로 진행.
3. `G:\VBox\VBoxManage.exe --version` 확인. 실패하면 VM 관련(G-4, G-5)만 멈추고 나머지는 진행.
4. Linear MCP 가 연결돼 있으면 `LOGH` 팀 사이클 1·2 이슈를 읽는다. 없으면 반영할 변경을 `docs/sessions/2026-09-28-linear-updates.md` 에 기록한다.
5. 서브에이전트 5개를 띄운다.

## 2. 결정(요약) — 상세는 요구사항 A-1
D-1 VirtualBox(G:) · D-2 GCP 전제·로컬 연습(과금 금지) · D-3 reverse-skill 로컬 main만 · D-4 용어 일본어 직역+나무위키 · D-5 Kotlin/JVM, Spring 없음(내장 Ktor), 웹 Next.js, 클라이언트측 도구 Rust.

## 3. 사전 승인(이 세션 한정)
- `E:\logh7` 커밋·push·PR·셀프 머지(리드만). `E:\reverse-skill` 로컬 main 커밋(push 금지).
- reverse-skill 스크립트: master-route, case-init(정적 `-Preset offline-sample`, VM 동적 `-Preset own-system`), case-guard, append-evidence, case-review, refresh-tool-index, 필요한 도구만 bootstrap.
- 다운로드·설치는 모두 **E:\**: Temurin JDK 25, Gradle, Maven Central 의존성, Docker Hub 공식 이미지, **Microsoft 공식 배포처(microsoft.com)의 Windows 10 x64 ISO**(보유 ISO 경로: ________ ).
- VirtualBox `E:\VM\logh7-win` 에 Windows 게스트 생성: **자동 로그인 + 빈 비밀번호**(사용자에게 로그인·비밀번호를 묻지 말 것), 시스템 로캘 ja-JP, OS·언어 기능 설치 중에만 NAT → **게임 실행 전 호스트 전용망**, 스냅샷 `clean`/`installed`.
- 게임 설치·클라이언트 실행은 VM 안에서만. 스텁은 localhost/호스트 전용 어댑터에만 바인딩.
- `E:\VM\myUbuntu` 안 Docker 설치·Compose 리허설(해당 VM은 NAT 허용).
- Linear `LOGH` 팀 이슈·문서·댓글 생성/수정.

**승인 안 함(필요하면 멈추고 질문)**: GCP 리소스 생성·과금, 호스트 시스템 설정 변경(Windows 기능·방화벽 등), 사용자 데이터 삭제, reverse-skill push, 외부 공개 게시, 원본 게임 파일 수정.

## 4. T0(리드) 할 일
- D-1~D-5를 Linear에 반영(LOGH-17·45·26·P6 의 Spring 표기 정정, LOGH-38 용어 기준, LOGH-41 GCP 전제).
- LOGH-5 VM 구축 → LOGH-6 설치 전후 비교(파일 해시·레지스트리) → T1 스텁이 준비되면 LOGH-20: VM에서 `exe\G7MTClient.exe <스텁 host> 47900 <세션명> 1 dummy` 실행 → 첫 0x34 블록 기록 → LOGH-22 프레이밍 Finding validated. 동적 케이스는 `work/logh7-dynamic-p2`(case-init `-Preset own-system`).
- 서브에이전트 결과 교차검증(파일 꼬리·문법·원문 전재 검사) → 주제별 작은 커밋 → PR → 셀프 머지.
- VM에서 3D 가속·DirectX 8 문제로 클라이언트가 뜨지 않으면 멈추고 대안(VMware 등)을 묻는다.

## 5. 완료와 보고
- 요구사항 A-3 G-1~G-9 달성 또는 미달성 사유·조치 기록.
- `review_case.py --verify-hashes --strict` PASS, reverse-skill 체크리스트(보고서·다이어그램·field-journal·references·index, 로컬 main).
- 보고서 `docs/sessions/2026-09-28-report.md`: ① 가정 ② 완료(읽음/실행함) ③ 못 한 것과 이유·필요 조치 ④ 리스크 3·다음 세션 첫 작업 3 ⑤ 사용자 미결 ⑥ 출처.
````

---

## D. 트랙 프롬프트 (리드가 서브에이전트에 그대로 전달)

공통 머리말(모든 트랙 앞에 붙인다):

````markdown
너는 LOGH7 부활 프로젝트의 서브에이전트다. 규칙은 `E:\logh7\AGENTS.md`. 요약: 문서는 한국어·작성자 "최병호"·근거 태그(evidence:manual|client|guess), 원문 장문 전재·게임 자산 커밋 금지, C:에 아무것도 쓰지 말 것(캐시 포함 E:), 도구 경로는 `E:\reverse-skill\skills\tool-index.md`, 새 도구는 `E:\Tools\`. **git 금지, 아래 쓰기 경로에만 쓴다. 파일을 쓴 뒤 끝부분을 다시 읽어 잘림이 없는지 확인한다.** Evidence는 `E:\reverse-skill\skills\scripts\append-evidence.ps1` 로 배정 번호 대역만 쓴다. 게임 클라이언트를 호스트에서 실행하지 않는다. 끝나면 10줄 이내로 결과·산출물 경로·미해결을 보고한다.
````

### T1 `server-core`
````markdown
[트랙 T1 server-core] Linear LOGH-17, LOGH-61, LOGH-18, LOGH-19. 쓰기: `server/`, `docs/protocol/server-notes.md`. Evidence E-200~.
1. `server/` 에 Gradle(Kotlin DSL) 멀티모듈: protocol · engine · gateway · ops-api · persistence · app. Kotlin 2.4, JDK 25(Temurin, `E:\Tools`에 설치), Netty 4.2, kotlinx.coroutines, Ktor 3.6(ops-api 내장, localhost 바인딩). **Spring 쓰지 말 것.** `GRADLE_USER_HOME=E:\Tools\gradle-home`. GitHub Actions 빌드 워크플로 1개(`.github/workflows/server.yml` 도 쓰기 허용).
2. protocol: 메시지 스키마(YAML) → Kotlin 코덱 생성 골격, 소켓 프레임 `[u16 len BE][payload]`(최대 0xF000), 프레임 종류 0x30/0x31/0x34~0x36, 봉투 `[u16 checksum][u32 seq][u16 body_len][body]`. 근거: `docs/protocol/protocol-draft.md` §1~§3.
3. Blowfish 변형(π 상수 바이트마다 +1, LE dword, 8바이트 ECB, 키 저장 XOR 0x17, 정적 핸드셰이크 키 GUID). T2가 `server/protocol/src/test/resources/vectors/` 에 넣는 벡터로 단위 테스트. 벡터가 아직 없으면 테스트 골격만 두고 표시.
4. gateway: TCP 47900 스텁(모든 바이트를 방향·시각과 함께 `E:\logh7\work\logh7-dynamic-p2\captures\` 에 기록, 키 교환 상태기계 골격), TCP 47902 업데이트 스텁(T2의 LOGH-11 결과로 "업데이트 없음" 응답).
5. 엔진 밖 코드는 월드 상태를 직접 바꾸지 않고 커맨드 큐로만(ADR-0001). `./gradlew build` 통과를 확인하고 결과를 보고.
````

### T2 `re-deep`
````markdown
[트랙 T2 re-deep] Linear LOGH-11, LOGH-14, LOGH-61 지원. 쓰기: `docs/protocol/`(server-notes.md 제외), `docs/re/`, `server/protocol/src/test/resources/vectors/`, `E:\logh7\work\logh7-client-triage\`. Evidence E-300~. reverse-skill 순서(master-route → 기존 케이스 `work/logh7-client-triage` case-guard 확인 → ghidra-reverse)를 따른다. 기존 Ghidra 프로젝트·`tools/ghidra/LoghExport.java` 재사용.
1. 키 교환 phase1~4(`0x00645180/2f0/660/7e8`) 페이로드 필드 맵, 봉투 체크섬 정확한 정의 → `docs/protocol/kex-envelope.md`.
2. **테스트 벡터**: 클라이언트 파일에서 변형 P/S 테이블(파일상 XOR 0x91)을 추출해 복원값 저장, 알려진 키·평문 → 암호문 벡터를 정적으로 계산해 `vectors/*.json`(hex). 계산 스크립트도 함께(`docs/protocol/` 아래 설명).
3. Login(0x0010, "GIN7")·LGLoginOK(0x7001)·LGLoginNG(0x7002)·0x0020 본문 필드 오프셋 → `docs/protocol/login-messages.md`.
4. 업데이터 0x6810/0x6820/0x6830 ↔ 0x8000/0x8001/0x8010/0x8020 본문과 "업데이트 없음" 응답 형식 → `docs/protocol/update-protocol.md`(LOGH-11).
5. 전략 지도(그리드 좌표·성계·행성) 데이터 위치·형식 → `docs/re/galaxy-map.md`(LOGH-14).
모든 결론은 candidate(동적 미검증). 주소는 VA로.
````

### T3 `l10n`
````markdown
[트랙 T3 l10n] Linear LOGH-12, LOGH-38, LOGH-36(준비). 쓰기: `tools/l10n/`, `docs/l10n/`. Evidence E-400~.
1. `tools/l10n/`: HFWR(magic, u32 0, N, G, u32[(G+3)&~3] 인덱스, N개 NUL 종료 cp932 문자열)·GFWR(UTF-16LE 금칙어) 읽기/쓰기. 원본 `E:\logh7-original\extracted\install\...\data\MsgDat\*.dat` 22개를 추출→재생성해 **SHA256 동일** 테스트. 추출 텍스트는 `E:\logh7\work\l10n\`(git 제외)에만. `$…$` 토큰 보존.
2. 용어집 `docs/l10n/glossary.md`: 기준은 **일본어 직역 + 나무위키 검색 표기**(출처 URL). 대상: 계급 23, 조직·직책(`docs/manual/data/org-posts.csv` 121개), 주요 인물·지명(`docs/l10n/glossary-sources.md` 목록), 게임 고유 용어. 확정 못 한 것만 "확인 필요".
3. 로캘·글꼴 패치 도구(사본 전용): `setlocale` 인자 문자열 `"Japanese"`→`"Korean"`, 글꼴명 `ＭＳ ゴシック`(0x0076e240, cp932 13바이트 슬롯)→한글 글꼴명, 원본 해시 보존·사본 해시 기록. 실제 표시 확인은 리드가 VM에서 한다.
````

### T4 `content-docs`
````markdown
[트랙 T4 content-docs] Linear LOGH-58, LOGH-15. 쓰기: `docs/manual/`. Evidence 불필요(문서 근거는 URL·페이지).
1. Wayback `http://gineiden.com/bin/backview.cgi?ct=update&page=N&num=N` 33건(목록: `docs/research/wayback-inventory.md` §2.2)을 읽고 **요약**(원문 전재 금지, 용어·짧은 구절만) → `docs/manual/09-patch-history.md`: 날짜·변경 규칙·관련 문서 절·U-번호 대응. 미구현 후보 표(`unimplemented-candidates.md`) 갱신. 가능하면 점검 공지(ct=mente)에서 서버 구성·장애 패턴만 요약.
2. 부록 CSV(`docs/manual/data/*.csv`)를 PDF p.56–100 페이지 이미지와 대조(`tools/manual_tables.py`, PyMuPDF는 `PYTHONPATH=E:\Tools\pylib`). 틀린 행은 추출 규칙을 고치거나 보정표로. 결과 → `docs/manual/08-organization-units-data.md` 검증 절.
````

### T5 `ops`
````markdown
[트랙 T5 ops] Linear LOGH-41, LOGH-42, LOGH-43. 쓰기: `deploy/`, `docs/ops/`. **GCP 리소스 생성·과금 금지(apply·create 명령 실행 금지).**
1. ADR-0005 개정안 `docs/ops/adr-0005-gcp-draft.md`(리드가 반영): GCP Compute Engine 전제 — 리전(서울 등)·머신 유형·고정 외부 IP·방화벽(TCP 47900/47902 + 세션 서버 포트)·영구 디스크·스냅샷 백업, 자체 PostgreSQL vs Cloud SQL 비교, 월 비용 추정(웹 확인·출처·확인일).
2. `deploy/docker-compose.yml`(game-server 자리표시자·postgres·prometheus·grafana), `deploy/README.md`.
3. `E:\VM\myUbuntu`(VirtualBox, `G:\VBox\VBoxManage.exe`)를 GCP VM 대역으로: Docker 설치·Compose 기동 리허설, 결과 기록. G: 가 없으면 로컬 Docker로 대체하고 그 사실을 보고.
4. GCP IaC 초안(Terraform 또는 gcloud 스크립트) `deploy/gcp/` — 실행하지 말고 `plan` 수준 설명만.
5. 백업·복구 설계 `docs/ops/backup.md`(스냅샷+이벤트 로그, 실 5분 이내 손실 목표).
````
