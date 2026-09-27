# 프로젝트 개요

작성: 최병호 · 2026-09-27

## 1. 대상 게임

| 항목 | 내용 | 근거 |
|---|---|---|
| 제목 | 『銀河英雄伝説 VII』(은하영웅전설 VII) | 설치 CD, 매뉴얼 |
| 개발·운영 | ボーステック(BOTHTEC) | 설치 CD `setup.ini` CompanyName, 이용약관 |
| 장르 | 다인원 온라인 전략 시뮬레이션. 플레이어는 캐릭터 1명으로 제국/동맹 조직에서 출세하며 진영 승리를 노림. 전략(턴 없는 실시간, ×24) + 전술(RTS) | 매뉴얼 W p.5, p.10 |
| 서비스 | 2004-05-14 개시, 2005-04 원작 판권사의 판권 허락 해지로 중단. 공식 서버 없음 | 사용자 제공 사실(재검증: docs/research/prior-work.md) |
| 요구 사양 | Windows 2000/XP 일본어판, DirectX 8.1, 1024×768 16bit | 매뉴얼 W p.6 |
| 원본 | archive.org `logh-7` (`Logh7.bin` MODE2/2352, 229,070,688 B) | E-001 |

## 2. 목표

1. 원본 클라이언트가 붙는 **대체 서버**(서버 에뮬레이터)
2. 클라이언트 **한국어화**
3. 원작의 **미구현·미완성 기능**과 소실된 **서버측 게임 로직 전체** 재구현
4. 실제 **온라인 운영**(계정·관리 툴·배포·모니터링)

## 3. 현재까지 확인한 것 (2026-09-27)

| 영역 | 내용 | 문서 |
|---|---|---|
| 원본 | archive.org 해시 일치, ISO 변환, InstallShield 7 캐비닛 전량 추출·MD5 검증(실패 0). 공식 추가 데이터 G7UPD040514(텍스처만) 확보 | tools/, docs/re/update-040514.md, E-001~E-003·E-007~E-008 |
| 클라이언트 구성 | `BootFirst.exe` → 업데이터 `Gin7UpdateClient.exe`(버전 131, `update.ini`) → 본체 `exe\G7MTClient.exe`. CD의 `G7Start.exe` 는 설치 메뉴. 데이터 `data\`(이미지·모델·사운드·`MsgDat\*.dat`) | docs/re/binary-inventory.md |
| 네트워크 | **MultiTerm MPS** 미들웨어, 원시 TCP. 게임 로그인 서버 기본 `202.8.80.179:47900`(명령행 인자로 변경 가능, 세션 서버는 로그인 응답이 지정), 업데이트 서버 `:47902`. **Blowfish 변형 + 키 교환 + 체크섬·시퀀스 봉투**로 암호화 | docs/re/connection-flow.md, docs/protocol/protocol-draft.md |
| 텍스트 | 대사 98%가 외부 `MsgDat`(HFWR, Shift_JIS), 변환은 `setlocale("Japanese")+mbstowcs` 한 곳 | docs/l10n/text-format.md |
| 규칙 | 매뉴얼 2판본 + 사이트 스냅샷을 시스템별로 정리, 부록 표 CSV화 | docs/manual/ |
| 미구현 | 후보 28건(U-01~U-28) | docs/manual/unimplemented-candidates.md |

## 4. 문서 지도

- 결정: [docs/adr/](adr/README.md)
- 규칙: [docs/manual/](manual/README.md)
- 역공학: [docs/re/](re/) · 프로토콜: [docs/protocol/](protocol/)
- 한국어화: [docs/l10n/](l10n/)
- 운영: [docs/ops/](ops/)
- 외부 조사: [docs/research/](research/)
- 계획: [docs/PLAN.md](PLAN.md) (단일 원천은 Linear `LOGH` 팀)
- 이번 세션 보고: [docs/BOOTSTRAP-REPORT.md](BOOTSTRAP-REPORT.md)
