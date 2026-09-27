# 매뉴얼 지식베이스

작성: 최병호 · 2026-09-27

『銀河英雄伝説Ⅶ』 공식 매뉴얼 두 판본과 공식 사이트 스냅샷을 게임 시스템별로 정리한 문서 모음. 서버 로직 재구현의 1차 근거(`evidence:manual`)다.

## 원자료

| 자료 | 위치 | 비고 |
|---|---|---|
| 웹판 `gin7manual.pdf` (101p, 2004-10-07) | `E:\manual-variants\internet-archive\` | **기준 판본.** 디지털 텍스트 레이어 있음 |
| CD판 `銀英伝７マニュアル.pdf` (69p, 2004-04-11) | 설치 CD(ISO) 루트 | 서비스 개시 시점 판본 |
| 공식 사이트 스냅샷 | `E:\manual-variants\wayback\*.utf8.txt` | 패치 리뷰·개발방침 공지·전술 소개 |

페이지 표기 `W p.N`(웹판), `CD p.N`(CD판)은 **PDF 페이지 번호**다(인쇄 쪽번호 아님). 원문은 저작물이므로 문서에는 한국어 요약과 짧은 원어 용어만 싣는다. 원문 텍스트 추출본은 `work\manual-kb\text\`(git 제외)에 있다.

## 문서

| 문서 | 내용 |
|---|---|
| [00-editions](00-editions.md) | 판본·스냅샷 목록, CD판↔웹판 차이, 개발방침 변경 로드맵 |
| [01-session-time-victory](01-session-time-victory.md) | 로그인·서버 선택·로그아웃, 세션(최대 2,000명), 시간(×24), 캐릭터 종류·이월, 승패 |
| [02-character](02-character.md) | 캐릭터 항목·능력치 8종·성장 |
| [03-communication](03-communication.md) | 메일·메신저·채팅·채널 |
| [04-strategy-core](04-strategy-core.md) | 직무권한 카드·CP·커맨드 군·그리드·워프·이동 |
| [05-personnel-operations-logistics](05-personnel-operations-logistics.md) | 평가/명성·계급 래더·승진/강등·임면·작전계획·병참 |
| [06-tactics](06-tactics.md) | 전술 게임 전반·커맨드·최고사령관 임무 |
| [07-command-table](07-command-table.md) | 전략 커맨드 81개(CP·시간·상태) |
| [08-organization-units-data](08-organization-units-data.md) | 부록 조직표·초기 배치·함종·병원 데이터(CSV) |
| [09-patch-history](09-patch-history.md) | Wayback 33 URL·18개 고유 공지 요약, 날짜·규칙·U 대응 |
| [unimplemented-candidates](unimplemented-candidates.md) | 미구현 후보 U-01~U-32, 수치 미기재 목록 |

## 데이터

`data/*.csv` — `tools/manual_tables.py` 기반 보정 추출기로 부록 표에서 추출(사실 데이터만). 재생성:

```powershell
$env:PYTHONPATH='E:\Tools\pylib'
$env:PYTHONDONTWRITEBYTECODE='1'
python docs/manual/rebuild-verified-tables.py E:\manual-variants\internet-archive\gin7manual.pdf
```

## 미결 질문

| ID | 질문 | 관련 |
|---|---|---|
| Q-M1 | 대령 이하 자동 승진 주기: "현실 매월 1일"(W p.35) vs "게임 30일마다"(W p.36) | 05 §2 |
| Q-M2 | 커맨드 표의 대기·소요 시간 단위(G시간 추정) | 07 |
| Q-M3 | 커맨드별 PCP/MCP 귀속 | 07 |
| Q-M4 | 웹판에서 빠진 CD판 규칙(제안·명령, 정치가 래더, 페잔)을 서버 기준으로 채택할지 | 00 §3, ADR-0003 |
