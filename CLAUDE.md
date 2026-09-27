# LOGH7 부활 프로젝트 — 작업 규칙

작성: 최병호 · 최초 작성 2026-09-27

서비스가 끝난 『銀河英雄伝説 VII』(BOTHTEC, 2004-05-14 개시 · 2005-04 종료) 클라이언트에 붙는 대체 서버, 한국어화, 소실된 서버 로직 재구현, 온라인 운영을 목표로 한다. 개요는 [docs/00-overview.md](docs/00-overview.md), 계획의 단일 원천은 Linear(요약: [docs/PLAN.md](docs/PLAN.md)).

## 1. 경로 규약

| 용도 | 경로 |
|---|---|
| 저장소(작업 루트) | `E:\logh7` |
| 사용자 원본(저장소 밖, 읽기 전용) | `E:\logh7-original\archive\` (bin/cue/xml), `E:\logh7-original\extracted\` (iso, install) |
| 원본 복원 | `tools\fetch-original.ps1` (다운로드→해시 검증→ISO→InstallShield 해제) |
| 분석 스킬 | `E:\reverse-skill` (행위 계약: `RULES.md`) |
| 매뉴얼·공식 사이트 스냅샷 | `E:\manual-variants` (`gin7manual.pdf` 가 시각적 진실 원천, `*_djvu.txt` OCR은 신뢰 금지) |
| 케이스 산출물 | `E:\logh7\work\<case>\` (git 제외) |
| 신규 도구 설치 | `E:\Tools\` (C: 드라이브 금지. 기설치 JDK21·Python311·Node·Ghidra 12.1.2 등은 그대로 사용) |
| Ghidra headless | `C:\Users\user\AppData\Local\Programs\Ghidra\ghidra_12.1.2_PUBLIC\support\analyzeHeadless.bat` |

`E:\logh7-revival` 에는 빈 `NUL` 파일만 있다. 건드리지 않는다.

## 2. 무엇을 커밋하지 않는가

- 원본 이미지(`Logh7.bin`/`.cue`), ISO, 캐비닛, 설치본의 exe/dll/dat/이미지/사운드 등 **게임 저작물은 커밋하지 않는다.** 해시·경로·복원 스크립트·포맷 문서만 커밋한다(`.gitignore` 참고).
- 매뉴얼·공식 사이트·게임 텍스트는 **요약·재서술**로 문서화하고, 원문 인용은 용어·짧은 구절만(출처 파일명·페이지 병기).
- GitHub 단일 파일 한도 100MiB. 대용량 가공물이 생기면 저장소 밖에 두고 경로·해시만 남긴다.

## 3. 분석 작업 순서 (reverse-skill)

```
1. E:\reverse-skill\RULES.md → skills\MASTER-ROUTING.md 읽기
2. master-route 로 PRIMARY 결정
   powershell -NoProfile -ExecutionPolicy Bypass -File E:\reverse-skill\skills\scripts\master-route.ps1 -Hint "<업무>" -ProjectRoot "E:\logh7"
3. case-init (사용자 소유 오프라인 복제본 → offline-sample)
   powershell -NoProfile -ExecutionPolicy Bypass -File E:\reverse-skill\skills\scripts\case-init.ps1 -Hint "<업무>" -CaseName "<케이스>" -ProjectRoot "E:\logh7" -Preset offline-sample -Sample "<파일>"
   → auth.status=granted, ready_for_act=true 확인 전 대상 ACT 금지 (case-guard.ps1 로 재확인)
4. PRIMARY SKILL.md 의 ACTION REQUIRED 실행
5. 도구 경로는 skills\tool-index.md 만 신뢰
6. 결론은 Evidence(E-nnn) → Finding(F-nnn) → Path(P-nnn), 과정은 timeline/workitems
```

- 주 경로: `ghidra-reverse`(정적) · `protocol-reverse`(opcode/프레이밍) · `thick-client`(접속 흐름) · 산출물 단계 `docs-generator`/`diagram-generator`/`case-review`.
- "읽음"과 "실행함"을 구분해 보고한다. Finding 을 `validated` 로 올리려면 독립 증거 2개 이상(정적 1 + 동적 1 권장).

## 4. 실행·격리

- 게임 설치·클라이언트 실행은 호스트에서 하지 않는다. 우선순위: VirtualBox VM → Windows Sandbox → (불가 시 사용자에게 확인).
- VM 은 **자동 로그인 + 빈 비밀번호(또는 매우 단순한 값)** 로 구성한다. 사용자에게 로그인·비밀번호를 요구하지 않는다.
- 클라이언트 네트워크는 loopback/호스트 전용 네트워크로 제한한다. 외부 대상에 대한 능동 스캔 금지.

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
- 한글화 용어: 1차 소설 이타카판(김완 역, 2011~; GC북스 리뉴얼판 동일 번역), 보조 1988년작 OVA의 한국어 더빙판(1992 대원동화 비디오, DNT 아님). 미확정은 `docs/l10n/glossary.md` 에 확인 대기로.

## 6. Git

- 기본 브랜치 `main`. 작업은 기능 브랜치 → PR → 셀프 머지.
- 커밋 메시지는 한국어 Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:` …), 작은 단위.
