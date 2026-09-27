# 부트스트랩 세션 보고서

작성: 최병호 · 세션일 2026-09-27 · 대상: LOGH7 부활 프로젝트(부트스트랩 & 계획 수립)

## 요약

- 저장소를 초기화하고, 원본(CD 이미지·공식 추가 데이터)을 확보·검증·전량 추출했다.
- 공식 매뉴얼 두 판본과 공식 사이트 스냅샷으로 **게임 규칙 지식베이스**를 만들고 부록 표를 CSV로 뽑았다. 미구현 후보 28건을 정리했다.
- 클라이언트를 정적으로 분석해 **무수정 접속 경로**(명령행 인자 + 로그인 응답의 세션 주소), **MPS 미들웨어와 암호 계층**, **텍스트·폰트 경로**를 확인했다.
- ADR 5건, Linear `LOGH` 팀(라벨 13·프로젝트 8·마일스톤 19·이슈 57·사이클 2·문서 2)을 채웠다.
- **동적 분석은 못 했다**: 격리 실행 환경이 없다(사용자 결정 필요).

---

## 1. 가정한 내용 (무응답·기본값 선택)

| # | 가정 | 근거/이유 |
|---|---|---|
| A-1 | 원본 이미지는 GitHub Release 대신 **`E:\logh7-original\`에 보관**하고 저장소엔 해시·복원 스크립트만 둔다 | 프롬프트 2.6이 허용한 두 선택지 중 하나. 공개 저장소로 게임 원본을 재배포하지 않기 위함 |
| A-2 | 게임 자산·매뉴얼/사이트 원문·추출 텍스트는 **커밋하지 않고 요약·사실 데이터만** 커밋 | 공개 저장소의 저작물 취급(리스크 R-10) |
| A-3 | 서버 스택은 Kotlin + Netty + Spring Boot + PostgreSQL(Redis 없음) → **세션 말미 개정: 게임 서버에서 Spring 제외(내장 Ktor), Kotlin/JVM 유지, 클라이언트측 도구는 Rust 1순위** | 프롬프트 Q5=D(에이전트 결정). ADR-0001 개정 2 |
| A-4 | 서버 규칙 기준 판본 = 웹판 매뉴얼(2004-10) + 패치 리뷰, CD판은 보조 | ADR-0003 |
| A-5 | 원작 "1988 OVA 한국어판"은 실제로 **1992 대원동화 대여 비디오 더빙판**으로 해석 | 조사 결과 TV 방영·자막판 없음(glossary-sources.md) |
| A-6 | "이타카(GC북스) 완전판" = 이타카판 김완 역(2011) 및 같은 번역의 GC북스 리뉴얼판(2025) | 조사 결과 |
| A-7 | 사이클 설정: 2주, 월요일 시작, 쿨다운 없음, 2개 자동 생성(Linear 기본값) | 사용자가 "사이클 활성화"만 지시 |
| A-8 | 프로젝트·마일스톤 목표일은 "가능한 한 빠른 오픈" 기준 추정치(알파 2026-12-20, 베타 2027-01-31) | 1인 개발 가정 |
| A-9 | 매뉴얼 커맨드 표의 대기·소요 시간 단위는 게임 시간(G시간), CP 종류는 커맨드 군으로 추정 | 매뉴얼에 명시 없음(Q-M2·Q-M3) |

## 2. 완료한 것

### 2.1 읽음(실행 없이 확인)
- `E:\reverse-skill`: RULES.md, MASTER-ROUTING.md, tool-index.md, thick-client·protocol-reverse·ghidra-reverse·docs-generator·diagram-generator SKILL.md, ops/scope-contract·evidence-finding-path·role-map, field-journal `_index.md`·템플릿·익명화 규칙
- `E:\manual-variants`: 매뉴얼 PDF 2판본 전 페이지 텍스트(디지털 텍스트 레이어), 부록·조직표·함종표 페이지 이미지 대조, 공식 사이트 스냅샷 11개(utf8.txt), 변형 대조표·아티팩트 인덱스
- 서브에이전트 산출물 전부(트랙 B 5문서, 트랙 C 6문서)

### 2.2 실행함(부작용 있음)
| 작업 | 결과 |
|---|---|
| 저장소 클론·초기 커밋·PR | `main` 초기 커밋 `fc3ae53`, PR [peppone-choi/logh7#1](https://github.com/peppone-choi/logh7/pull/1) 머지, PR #2(이 보고서 포함) |
| reverse-skill 스크립트 | `master-route.ps1` ×3, `case-init.ps1 -Preset offline-sample`, `case-guard.ps1` ×2, `append-evidence.ps1`(E-001~E-008), `case-review/review_case.py --strict` → **PASS** |
| 원본 다운로드·검증 | `Logh7.bin/.cue/files.xml`(해시 일치), `G7UPD040514.exe`(사용자 승인 후, Wayback digest 일치) |
| 추출 | ISO 변환, InstallShield 7 캐비닛 2,219 항목(실패 0), 업데이트 패키지 275 항목(실패 0) |
| 도구 설치 | PyMuPDF 1.28.2 → `E:\Tools\pylib` (pip --target) |
| 정적 분석 | Ghidra 12.1.2 headless(트랙 B), pefile/capstone 스크립트 |
| Linear | `LOGH` 팀(사용자가 기존 팀 이름 변경) 사이클 활성화(Chrome, 사용자 지시), 라벨 13, 프로젝트 8, 마일스톤 19, 이슈 LOGH-5~61(57건, 완료 8), 의존 관계, 문서 2 |
| reverse-skill 회신 | field-journal 1건·references 2건·`_index.md` 갱신 → **로컬 브랜치** `journal/2026-09-27-mps-mmo-client-triage`에만 커밋(원격 push 없음) |
| 메모리 | VM 자동 로그인·빈 비밀번호 선호 기록 |

### 2.3 산출물
- 문서: `CLAUDE.md`, `README.md`, `docs/00-overview.md`, `docs/PLAN.md`, `docs/adr/0001~0005`, `docs/manual/`(10문서 + CSV 6), `docs/re/`(인벤토리·접속 흐름·함수 맵·업데이트 해체·케이스 보고서), `docs/protocol/protocol-draft.md`, `docs/l10n/`(텍스트 포맷·용어집·용어 근거), `docs/ops/`(격리 VM·구성도), `docs/research/`(선행 작업·Wayback·도구 버전·노출 방식·출처)
- 도구: `tools/fetch-original.ps1`, `bin2iso.py`, `isextract.py`, `is_sfx_extract.py`, `petriage.py`, `manual_tables.py`, `tools/ghidra/LoghExport.java`·`run_logh7_export.ps1`
- 다이어그램(Mermaid): 접속 시퀀스(`docs/re/connection-flow.md`, 케이스 보고서 P-001), 목표 구성도·분석 구성도(`docs/ops/architecture.md`)
- 케이스: `work/logh7-client-triage/`(Evidence 27, Finding 8, Path 3, git 제외)

### 2.4 핵심 발견
1. 클라이언트는 `G7MTClient.exe <host> <port> <세션명> …` 인자로 로그인 서버를 지정하고, 로그인 응답 0x7001이 세션 서버 주소를 준다 → **클라이언트 무수정 접속** 가능(E-011·E-018·E-028).
2. 통신은 **MultiTerm MPS**, `[u16 len BE][frame type]`, **Blowfish 변형**(π 상수 바이트+1 등) + 정적 GUID 핸드셰이크 키 + 키 교환 4단계 + 체크섬·시퀀스 봉투(E-014~E-016).
3. 텍스트 98%가 외부 `MsgDat`(HFWR), 변환이 `setlocale("Japanese")+mbstowcs` 한 곳, 글꼴 charset DEFAULT → 문자열 2곳 패치 + 리소스 재인코딩으로 한글화 가능성(ADR-0004).
4. 자기검증·패커·안티디버그 없음(E-022).
5. 공식 추가 데이터는 텍스처뿐 → 버전 131 이후 exe 갱신분은 보존되지 않았을 가능성(F-008).
6. 매뉴얼 두 판본 차이로 운영 중 변경(성장·삭제·작전·병참 추가, 페잔·제안/명령 절 삭제)과 개발방침 변경 로드맵을 확인.
7. 클라이언트 메시지 이름표에서 미구현 후보 9건의 흔적(공전·시설 건설·세율·최고사령관 임무·수송 등)을 찾음 → "서버만 없던" 기능이 많다.

## 3. 못 한 것과 이유·필요 조치

| # | 못 한 것 | 이유 | 필요 조치 |
|---|---|---|---|
| X-1 | **격리 VM 구축·실제 설치·클라이언트 실행(동적 분석 전부)** | VirtualBox 설치 경로 `G:\VBox\`의 G: 드라이브 없음, Windows 게스트 ISO 없음, Windows Sandbox 기능 꺼짐. 시스템 기능 켜기는 제가 대신 할 수 없음 | 사용자 결정 1건(아래 5-1). 결정 후 LOGH-5 → LOGH-6·LOGH-20 |
| X-2 | 모든 Finding의 validated 승격(원본 무결성 제외) | 동적 증거 없음 | X-1 해소 후 P2에서 |
| X-3 | 트랙 A(매뉴얼) 서브에이전트 운용 | 첫 지시문 전달이 중간에 끊겨 에이전트를 중지하고 **메인이 직접 수행**(결과물은 동일 범위) | 없음 |
| X-4 | 부록 CSV 전 페이지 이미지 대조 | 시간 배분(p.63·p.79만 1:1 검증) | LOGH-15 |
| X-5 | Wayback 패치노트 33건·점검 공지 텍스트화 | 범위가 커서 후속 이슈로 | LOGH-58 |
| X-6 | 용어집 "확인 필요" 해소 | 이타카판 실물 대조 필요, OVA 더빙 표기 자료 없음 | LOGH-38(사용자 확인) |
| X-7 | reverse-skill 변경의 원격 반영 | 제3자 원본 저장소(`zhaoxuya520/reverse-skill`)라 기여 여부는 사용자 결정 | 아래 5-4 |
| X-8 | `refresh-tool-index` 재실행 | 인덱스가 오늘 13:54 생성본이고 manifest 도구를 새로 설치하지 않음(PyMuPDF는 비대상) | 도구 설치 시 실행 |
| X-9 | 공개 노출 방식 확정 | 자택 회선 정보 필요 | 아래 5-3 |

참고: 세션 중 출처를 확인할 수 없는 **미완성 스크립트 `tools/pe_triage.py`** 와 트랙 B가 쓰다 끊긴 `tools/ghidra/ExportNetTriage.java` 가 생겨, 대체본이 있어 둘 다 삭제했다(커밋되지 않음).

## 4. 가장 큰 리스크 3개와 다음 세션 첫 작업 3개

리스크 전체는 Linear 문서 [리스크 레지스터](https://linear.app/peppone-choi/document/리스크-레지스터-96c8a378d7ff).

| 리스크 | 내용 |
|---|---|
| **R-03 격리 환경 부재** | 동적 분석·접속 성립을 전부 막고 있음. 사용자 결정 한 번으로 해소 |
| **R-01 MPS 암호 계층** | 봉투·키 교환 세부가 동적 확인 전까지 불확실. 접속 성립의 기술적 병목 |
| **R-08 범위 과다** | 1인 개발 대비 범위가 큼 → 최소 루프 알파(2026-12-20)부터 |

| 다음 세션 첫 작업 | Linear |
|---|---|
| 1. 격리 VM 준비(자동 로그인·빈 비밀번호, ja-JP 로캘, 호스트 전용망) | LOGH-5 |
| 2. 서버 저장소 골격(Gradle Kotlin 멀티모듈, JDK 25, CI) | LOGH-17 |
| 3. MPS 암호 계층(Blowfish 변형 테스트 벡터 → 키 교환 상태기계) | LOGH-61 |

## 5. 사용자에게 물어볼 미결 항목

1. **격리 실행 환경(1.5)**: ① G: 드라이브를 다시 연결해 VirtualBox 사용 ② VMware Workstation + Windows ISO(버전·경로 알려주기) ③ Windows Sandbox 기능 켜기(직접) — 어느 쪽으로 할까요?
2. **서버 스택(1.5)**: ADR-0001 개정 2 — Kotlin/JVM 유지, 게임 서버에서 Spring 제외(내장 Ktor), 웹 Next.js, 클라이언트측 도구 Rust. C#에 익숙하시면 C#/.NET이 역전하므로 알려 주세요.
3. **자택 서버 공개 노출(1.5)**: 자택 회선이 공인 IP인지(CGNAT 여부), 공유기 포트포워딩 가능 여부, 서버로 쓸 기기 OS·상시 가동 여부.
4. **reverse-skill 기여**: 로컬 브랜치의 field-journal·references를 원 저장소에 PR로 기여할지, 로컬 main에만 합칠지, 그대로 둘지.
5. **용어 확인**: 이타카판 실물 대조(주요 인물 전체 성명·계급·직책), 게임 고유 용어 번역어(계급 래더·그리드·스폿·숙련도·커맨드 레인지 서클).
6. **매뉴얼 해석 미결(Q-M1~Q-M4)**: 대령 이하 자동 승진 주기, 커맨드 표 시간 단위, PCP/MCP 귀속, CD판 전용 규칙(제안·명령, 정치가 래더, 페잔) 채택 여부.

## 6. 조사에 사용한 출처

전체 목록은 [docs/research/sources.md](research/sources.md). 주요 출처:

- 원본: https://archive.org/details/logh-7 · https://archive.org/details/gin7manual · https://web.archive.org/web/20040625193252/http://gineiden.com:80/G7UPD040514.exe
- 공식 사이트 보관본: `http://web.archive.org/cdx/search/cdx?url=gineiden.com/*` (update.html, ns_update.html, update01~05.html, st_tactics*.html, manual.html, sv_end.html, ev041015.html, qa*.html 등)
- 미들웨어: https://ja.wikipedia.org/wiki/マルチターム · https://web.archive.org/web/20060823224452/http://www.multiterm.co.jp:80/mps/function/detail.html
- 같은 MPS 게임 공개 자료: https://github.com/tofuman0/SBOL-Server · https://github.com/tofuman0/MPS-Blowfish · https://web.archive.org/web/20200814080543/http://wiki.sb-online.net/index.php?title=Protocol&printable=yes
- 추출 도구 참고: https://github.com/twogood/unshield (lib/file.c, libunshield.c)
- 종료 경위: https://game.watch.impress.co.jp/docs/20050414/ginga.htm · https://www.4gamer.net/games/010/G001079/
- 도구·스택 버전: GitHub Releases(Ghidra·Netty·Kotlin·Spring Boot 등), https://api.adoptium.net/v3/info/available_releases · https://nodejs.org/dist/index.json · https://www.postgresql.org/versions.json
- GitHub 한도: https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github · https://docs.github.com/en/billing/concepts/product-billing/git-lfs
- 공개 노출: https://ngrok.com/pricing · https://developers.cloudflare.com/spectrum/ · https://playit.gg
- 용어: https://www.aladin.co.kr/shop/wproduct.aspx?ItemId=13518038 (이타카판 서지) · https://namu.wiki/w/은하영웅전설/애니메이션 · https://namu.wiki/w/은하영웅전설/등장인물
