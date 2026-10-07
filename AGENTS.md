# LOGH7 부활 프로젝트 — 작업 규칙 (AGENTS.md)

작성: 최병호 · 최초 작성 2026-09-27 · 이 파일이 에이전트 규칙의 **단일 원천**이다(Codex는 이 파일을, Claude Code는 `CLAUDE.md`를 통해 이 파일을 읽는다).

서비스가 끝난 『銀河英雄伝説 VII』(BOTHTEC, 2004-05-14 개시 · 2005-04 종료) 클라이언트에 붙는 대체 서버, 한국어화, 소실된 서버 로직 재구현, 온라인 운영을 목표로 한다. 개요는 [docs/00-overview.md](docs/00-overview.md), 계획의 단일 원천은 Linear `LOGH` 팀(요약: [docs/PLAN.md](docs/PLAN.md)), 결정은 [docs/adr/](docs/adr/README.md).

## 1. 경로 규약

| 용도 | 경로 |
|---|---|
| 저장소(작업 루트) | `E:\logh7` |
| 사용자 원본(저장소 밖, 읽기 전용) | `E:\logh7-original\archive\` (bin/cue/xml/업데이트 exe), `E:\logh7-original\extracted\` (iso, install, G7UPD040514) |
| 원본 복원 | `tools\fetch-original.ps1` (다운로드→해시 검증→ISO→InstallShield 해제) |
| 분석 스킬 | `E:\reverse-skill` (행위 계약: `RULES.md`, 진입: `AGENTS.md`) |
| 매뉴얼·공식 사이트 스냅샷 | `E:\manual-variants` (`gin7manual.pdf` 는 디지털 텍스트 레이어 있음, `*_djvu.txt` OCR은 신뢰 금지) |
| 케이스 산출물 | `E:\logh7\work\<case>\` (git 제외) |
| 신규 도구 설치 | `E:\Tools\` (VirtualBox만 사용자 승인 예외 `E:\VirtualBox\`) |
| VM | `E:\VM\` (VirtualBox 본체는 `E:\VirtualBox\`, 2026-09-27 재설치) |
| Ghidra headless | `C:\Users\user\AppData\Local\Programs\Ghidra\ghidra_12.1.2_PUBLIC\support\analyzeHeadless.bat` |

- **C: 드라이브 여유가 약 7GB뿐이다. C:에 설치·캐시·VM·ISO를 두지 않는다.** (예: `GRADLE_USER_HOME=E:\Tools\gradle-home`, npm/pip 캐시도 E:)
- `E:\logh7-revival` 에는 빈 `NUL` 파일만 있다. 건드리지 않는다.
- 호스트는 Windows 10 Pro. 명령은 PowerShell 기준(경로 `E:\...`).

## 2. 무엇을 커밋하지 않는가

- 원본 이미지(`Logh7.bin`/`.cue`), ISO, 캐비닛, 설치본의 exe/dll/dat/이미지/사운드 등 **게임 저작물은 커밋하지 않는다.** 해시·경로·복원 스크립트·포맷 문서만 커밋한다(`.gitignore` 참고).
- 매뉴얼·공식 사이트·게임 텍스트는 **요약·재서술**로 문서화하고, 원문 인용은 용어·짧은 구절만(출처 파일명·페이지 병기). 커밋 전 "연속 일본어 40자 이상" 구간이 없는지 확인한다.
- GitHub 단일 파일 한도 100MiB. 대용량 가공물은 저장소 밖에 두고 경로·해시만 남긴다.

## 3. 작업·분석 방식 (2026-10-07 단순화, D-8)

- Linear 이슈의 목표·완료 조건과 필요한 기존 코드·문서만 확인하고 작업한다. 이미 확인한 내용을 새 원장으로 복제하지 않는다.
- 로컬에서는 변경 범위에 필요한 검사와 실제 재현만 실행한다. 전체 빌드·전체 검사는 CI 또는 해당 변경에 필요한 경우에만 수행한다. 결과는 관련 PR/CI 링크와 통과·실패·미확인 내용을 짧게 기록한다.
- **작업마다 Evidence/Finding/Path 번호, SHA 영수증, 별도 보고서·다이어그램·field-journal, 자동 리뷰를 의무화하지 않는다.** 기본 기록은 변경 내용·확인 결과·남은 문제다. 원바이트·캡처는 재현이나 결론에 필요한 것만 `work/`에 보존한다.
- 바이너리·프로토콜을 실제로 해석할 때는 `E:\reverse-skill`의 필요한 스킬과 `tool-index.md`를 참고한다. 기존 자료로 해결할 수 있으면 새 분석 케이스를 만들지 않는다. 사용자 승인 범위·원본 보호·로컬 네트워크 제한은 계속 지킨다.
- 새 대상에 능동 작업을 할 때는 승인과 범위를 먼저 확인한다. 정식 케이스가 필요하면 `scope.md`와 case-guard를 사용한다. 단순 구현·문서 수정·이미 승인된 로컬 시험에 패키지 전체 절차를 강제하지 않는다.
- "읽음", "실행함", "통과", "미확인"은 구분한다. 추정 원인을 확정으로 쓰지 않고 실제 완료 조건을 충족했을 때만 완료로 처리한다.
- 기존 번호형 케이스를 정식으로 검토하는 경우에는 그 케이스 형식과 `review_case.py --verify-hashes --strict`를 따른다. 새 작업에 자동 적용하지 않는다.
- reverse-skill 에 기록을 남기는 경우(field-journal·references·index)는 **로컬 main 에만 커밋, push 금지**.

## 4. 실행·격리

- **클라이언트 실행은 호스트의 별도 비활성 Windows 데스크톱을 기본으로 한다(2026-10-07 사용자 결정 D-7).** Breaking Point의 `CreateDesktop` + `STARTUPINFO.lpDesktop` 방식을 사용한다. 원본은 읽기 전용으로 보존하고 `work/<case>/` 사본만 실행한다. 실행·캡처·종료는 CLI로 수행한다. 설치 프로그램 실행이나 호스트 시스템 설정 변경은 이 결정에 포함하지 않는다. [실행 방법](docs/ops/background-client.md). `evidence:client` (Breaking Point 실행 기록), `evidence:guess` (LOGH7 기본 실행 정책; 사용자 결정).
- 비활성 데스크톱은 화면·입력의 분리이며 파일·레지스트리·네트워크를 격리하는 VM은 아니다. 로컬 스텁 주소를 명령행으로 지정하고 대상 프로세스 창만 캡처한다. **VM은 멀티플레이 시험에 필요할 때만 사용한다(2026-10-07 사용자 추가 결정).** 단일 클라이언트의 실행 실패나 로캘 문제를 이유로 VM으로 되돌리지 않는다. `evidence:guess`
- VM 은 **자동 로그인 + 빈 비밀번호(또는 매우 단순한 값)** 로 구성한다. 사용자에게 로그인·비밀번호를 요구하지 않는다.
- 호스트 시험은 `127.0.0.1` 스텁에만 접속한다. VM을 쓰는 경우 클라이언트 네트워크는 호스트 전용망이며 OS·언어 기능 설치 중에만 NAT를 허용한다. 스텁 서버는 localhost/호스트 전용 어댑터에만 바인딩.
- **VM·게스트는 헤드리스 CLI로만 다룬다(2026-09-28 사용자 결정 D-6).** 사용자는 같은 호스트로 다른 일을 하므로 호스트 데스크톱을 건드리지 않는다.
  - VirtualBox: `VBoxManage startvm <vm> --type headless`, `guestcontrol <vm> run`/`copyto`/`copyfrom`(계정은 `--passwordfile`, 값은 채팅·문서·로그에 쓰지 않음), `controlvm <vm> screenshotpng`, `controlvm <vm> acpipowerbutton`, `snapshot`, `modifyvm`.
  - VMware: `vmrun -T ws start <vmx> nogui`, `runProgramInGuest`/`runScriptInGuest`, `copyFileFromHostToGuest`/`copyFileFromGuestToHost`, `captureScreen`, `stop <vmx> soft`.
  - 금지: computer-use·호스트 활성 데스크톱의 화면/입력/브라우저 자동화, VirtualBox 관리자·VMware Workstation·VNC 뷰어·VM 콘솔 창 열기, 호스트 활성 데스크톱에 창이나 포커스를 띄우는 명령, 사용자에게 게스트 화면 클릭·확인 요청. 소유 비활성 데스크톱의 테스트 프로세스만 대상으로 하는 CLI 입력·캡처는 D-7에 따라 허용한다. `SwitchDesktop`·전역 `SendInput`·실제 마우스 이동은 금지한다.
  - 게스트 GUI 프로세스(게임 클라이언트 등)는 guestcontrol로 등록한 작업 스케줄러 `LogonType Interactive` 임시 작업이나 `vmrun runProgramInGuest -interactive`로 대화형 세션에 띄우고, 끝나면 임시 작업을 지운다. 화면 확인은 파일로 저장한 스크린샷으로 한다.
  - UAC 승인처럼 CLI로 안 되는 단계는 GUI로 우회하지 말고 멈추고 묻는다.
- 외부 대상에 대한 능동 스캔 금지. GCP 리소스 생성·과금 작업은 별도 승인 전 금지(IaC·스크립트 초안만).

## 5. 문서 규칙

- 산출 문서는 한국어, 작성자 표기 **최병호**. 코드 식별자·파일명·명령은 영어.
- 매뉴얼 규칙·바이너리 해석·설계 추정의 구분이 필요한 주장에 아래 근거 태그를 쓴다. 단순 작업 기록·검사 결과마다 반복하지 않는다.

| 태그 | 의미 |
|---|---|
| `evidence:manual` | 매뉴얼/공식 사이트 근거 (가장 강함) |
| `evidence:client` | 클라이언트 바이너리/실행 관찰 |
| `evidence:guess` | 위 둘로 확정 못 한 설계 추정 |

- 서버 로직 결정 순서: 매뉴얼 → 클라이언트 동작 → 설계 추정. 확정과 추정을 섞지 않는다.
- "미구현" 단기 범위 = 원작 매뉴얼·공식 사이트·패치노트에 존재했거나 "준비 중"으로 예고된 기능 + 서버 소멸로 사라진 서버측 로직. 원작에 없던 신규 아이디어는 백로그(`content` + `backlog`)에만.
- **한글화 용어(2026-09-27 사용자 결정 D-4)**: 당분간 **일본어 직역 + 나무위키 검색 표기**를 쓴다(출처 URL 병기). 이타카판(김완 역) 실물 대조는 보류. 미확정은 `docs/l10n/glossary.md` 에 확인 대기로.
- 버전·도구·가격 등 최신 정보는 웹으로 확인하고 출처를 남긴다.

## 6. 현재 확정 결정 (요약, 상세는 ADR)

| ID | 결정 | 근거 |
|---|---|---|
| D-1 | VirtualBox(`E:\VirtualBox`) 환경 보존. 멀티플레이 시험에 필요할 때만 사용하며 기본 클라이언트 실행은 D-7 | 사용자 2026-09-27 추가 승인, 2026-10-07 갱신 |
| D-2 | 배포는 **GCP 전제**, 지금은 로컬에서 연습(로컬 Ubuntu VM `E:\VM\myUbuntu` 또는 Docker) | 사용자 2026-09-27, ADR-0005 |
| D-3 | reverse-skill 회신은 로컬 main 에만 | 사용자 2026-09-27 |
| D-4 | 용어: 일본어 직역 + 나무위키 | 사용자 2026-09-27 |
| D-5 | 게임 서버 **Kotlin/JVM + Netty + 코루틴, Spring 없음(내장 Ktor 운영 API)**, 웹 Next.js, 플레이어용 런처·패치 적용기·DLL 은 Rust 1순위, 프로토콜은 스키마→코덱 생성 | ADR-0001 개정 2 |
| D-6 | VM·게스트는 헤드리스 CLI로만 조작, computer-use·GUI 창·사용자 클릭 요청 금지(§4) | 사용자 2026-09-28 |
| D-7 | 단일 클라이언트는 호스트 비활성 데스크톱에서 사본을 CLI 실행·캡처. VM은 멀티플레이에 필요할 때만. 활성 데스크톱·전역 입력 금지는 유지 | 사용자 2026-10-07 및 추가 결정, ADR-0006 |
| D-8 | 오픈삼국 방식 참고: 변경 범위 검사와 짧은 결과 중심. 매 작업의 증거 번호·SHA 영수증·별도 보고서·자동 리뷰 의무 제거 | 사용자 2026-10-07, §3 |
| — | 접속: 원본 클라이언트를 명령행 인자(host/port/세션명)로 실행 + 서버가 LGLoginOK 에서 세션 주소 반환(클라이언트 무수정) | ADR-0002 |
| — | 한국어화: 로캘 전환(CP949, 문자열 2곳 패치 + MsgDat 재작성) 우선, CP932 호환 사설 매핑 대체 | ADR-0004 |

## 7. 에이전트 협업 규칙 (리드/서브에이전트 공통)

- 게임 실행·입력·캡처는 한 채팅의 단일 담당이 관리한다. **게임 실행이 아닌 독립 작업은 필요할 때 Codex 앱 내 채팅을 병렬로 진행해도 된다(사용자 2026-10-07).** 병렬화·새 채팅·위임을 매 작업의 의무로 만들지 않는다.
- 리드(메인)만 git 을 조작한다(커밋·push·PR·머지). 서브에이전트는 git 금지, **배정된 경로에만** 쓴다.
- 파일을 쓴 뒤 **끝부분을 다시 읽어 잘림이 없는지 확인**한다(과거 잘린 파일 2건 발생).
- 정식 분석에서 Evidence 번호를 사용할 때만 트랙별 대역을 나눠 쓴다(충돌 방지).
- 서브에이전트는 끝나면 10줄 이내 요약(결과·산출물 경로·미해결)을 리드에게 돌려준다.
- 원본 게임 파일은 수정하지 않는다(패치·가공은 사본에만, 원본 해시 보존).

## 8. Git

- 기본 브랜치 `main`. 작업은 기능 브랜치 → PR → 셀프 머지.
- 커밋 메시지는 한국어 Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:` …), 작은 단위.
- 커밋 메시지·PR 서명(공동 작성자 표기)은 그 세션의 도구 규칙을 따른다.
