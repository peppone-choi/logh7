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

## 3. 분석 작업 순서 (reverse-skill)

```
1. E:\reverse-skill\RULES.md → skills\MASTER-ROUTING.md 읽기
2. master-route 로 PRIMARY 결정
   powershell -NoProfile -ExecutionPolicy Bypass -File E:\reverse-skill\skills\scripts\master-route.ps1 -Hint "<업무>" -ProjectRoot "E:\logh7"
3. case-init
   정적(사용자 소유 오프라인 복제본): -Preset offline-sample -Sample "<파일>"
   VM 동적 분석(격리 랩):            -Preset own-system  (network_profile = lab_only)
   powershell -NoProfile -ExecutionPolicy Bypass -File E:\reverse-skill\skills\scripts\case-init.ps1 -Hint "<업무>" -CaseName "<케이스>" -ProjectRoot "E:\logh7" <preset 옵션>
   → auth.status=granted, ready_for_act=true 확인 전 대상 ACT 금지 (case-guard.ps1 로 재확인)
4. PRIMARY SKILL.md 의 ACTION REQUIRED 실행
5. 도구 경로는 E:\reverse-skill\skills\tool-index.md 만 신뢰
6. 결론은 Evidence(E-nnn) → Finding(F-nnn) → Path(P-nnn), 과정은 timeline/workitems
```

- 주 경로: `ghidra-reverse`(정적) · `protocol-reverse`(opcode/프레이밍) · `thick-client`(접속 흐름) · 산출물 단계 `docs-generator`/`diagram-generator`/`case-review`.
- "읽음"과 "실행함"을 구분해 보고한다. Finding 을 `validated` 로 올리려면 독립 증거 2개 이상(정적 1 + 동적 1 권장).
- case-review 계약: Finding 은 `- severity:` `- status:` `- confidence:` `- location:` `- evidence_ids:` 를 각각 한 줄에, Path 에는 `- path_type:` 을 둔다. `review_case.py --verify-hashes --strict` 로 확인.
- reverse-skill 에 남기는 기록(field-journal·references·index)은 **로컬 main 에만 커밋, push 금지**.

## 4. 실행·격리

- 게임 설치·클라이언트 실행은 호스트에서 하지 않는다. **VirtualBox VM**(`E:\VirtualBox\VBoxManage.exe`) 안에서만.
- VM 은 **자동 로그인 + 빈 비밀번호(또는 매우 단순한 값)** 로 구성한다. 사용자에게 로그인·비밀번호를 요구하지 않는다.
- 게임 클라이언트 VM 네트워크는 호스트 전용망. OS·언어 기능 설치 중에만 NAT 허용. 스텁 서버는 localhost/호스트 전용 어댑터에만 바인딩.
- 외부 대상에 대한 능동 스캔 금지. GCP 리소스 생성·과금 작업은 별도 승인 전 금지(IaC·스크립트 초안만).

## 5. 문서 규칙

- 산출 문서는 한국어, 작성자 표기 **최병호**. 코드 식별자·파일명·명령은 영어.
- 모든 규칙·주장에 근거 태그를 단다.

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
| D-1 | 게임 클라이언트 격리 실행은 VirtualBox(`E:\VirtualBox`, 기존 G: 부재로 재설치) | 사용자 2026-09-27 추가 승인 |
| D-2 | 배포는 **GCP 전제**, 지금은 로컬에서 연습(로컬 Ubuntu VM `E:\VM\myUbuntu` 또는 Docker) | 사용자 2026-09-27, ADR-0005 |
| D-3 | reverse-skill 회신은 로컬 main 에만 | 사용자 2026-09-27 |
| D-4 | 용어: 일본어 직역 + 나무위키 | 사용자 2026-09-27 |
| D-5 | 게임 서버 **Kotlin/JVM + Netty + 코루틴, Spring 없음(내장 Ktor 운영 API)**, 웹 Next.js, 플레이어용 런처·패치 적용기·DLL 은 Rust 1순위, 프로토콜은 스키마→코덱 생성 | ADR-0001 개정 2 |
| — | 접속: 원본 클라이언트를 명령행 인자(host/port/세션명)로 실행 + 서버가 LGLoginOK 에서 세션 주소 반환(클라이언트 무수정) | ADR-0002 |
| — | 한국어화: 로캘 전환(CP949, 문자열 2곳 패치 + MsgDat 재작성) 우선, CP932 호환 사설 매핑 대체 | ADR-0004 |

## 7. 에이전트 협업 규칙 (리드/서브에이전트 공통)

- 리드(메인)만 git 을 조작한다(커밋·push·PR·머지). 서브에이전트는 git 금지, **배정된 경로에만** 쓴다.
- 파일을 쓴 뒤 **끝부분을 다시 읽어 잘림이 없는지 확인**한다(과거 잘린 파일 2건 발생).
- Evidence 번호는 트랙별 대역을 나눠 쓴다(충돌 방지).
- 서브에이전트는 끝나면 10줄 이내 요약(결과·산출물 경로·미해결)을 리드에게 돌려준다.
- 원본 게임 파일은 수정하지 않는다(패치·가공은 사본에만, 원본 해시 보존).

## 8. Git

- 기본 브랜치 `main`. 작업은 기능 브랜치 → PR → 셀프 머지.
- 커밋 메시지는 한국어 Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:` …), 작은 단위.
- 커밋 메시지·PR 서명(공동 작성자 표기)은 그 세션의 도구 규칙을 따른다.
